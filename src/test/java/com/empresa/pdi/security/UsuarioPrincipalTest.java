package com.empresa.pdi.security;

import com.empresa.pdi.enums.PerfilUsuario;
import com.empresa.pdi.model.Usuario;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;

class UsuarioPrincipalTest {
    private static final UUID USUARIO_ID = UUID.fromString("77e6c804-d148-423d-a4f2-182db3ebba71");
    private static final String NOME = "Usuario do teste unitario";
    private static final String EMAIL = "identidade-unitaria@example.invalid";
    // Deliberately not a BCrypt hash: this suite does not encode or validate passwords.
    private static final String HASH_FICTICIO = "encoded-local-credential-for-unit-test";

    @ParameterizedTest
    @CsvSource({
            "COLABORADOR, ROLE_COLABORADOR",
            "GESTOR, ROLE_GESTOR",
            "RH_ADMIN, ROLE_RH_ADMIN"
    })
    void concedeSomenteAuthorityCorrespondenteAoPerfilCadastrado(
            PerfilUsuario perfil, String authorityEsperada) {
        UsuarioPrincipal principal = new UsuarioPrincipal(usuario(perfil), HASH_FICTICIO);

        assertEquals(perfil, principal.getPerfilAcesso());
        assertEquals(List.of(authorityEsperada), principal.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .toList());
    }

    @Test
    void preservaIdentidadeCopiadaSemDependerDeAlteracoesPosterioresNaEntidade() {
        Usuario usuario = usuario(PerfilUsuario.COLABORADOR);
        UsuarioPrincipal principal = new UsuarioPrincipal(usuario, HASH_FICTICIO);

        usuario.setNome("Nome alterado depois da autenticacao");
        usuario.setEmail("outro-usuario@example.invalid");
        usuario.setPerfilAcesso(PerfilUsuario.RH_ADMIN);
        usuario.setAtivo(false);

        assertIdentidade(principal, PerfilUsuario.COLABORADOR);
        assertTrue(principal.isEnabled());
        assertEquals(List.of("ROLE_COLABORADOR"), principal.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .toList());
    }

    @Test
    void refleteSituacaoInativaDoCadastroNoMomentoDaConstrucao() {
        Usuario usuario = usuario(PerfilUsuario.COLABORADOR);
        usuario.setAtivo(false);

        UsuarioPrincipal principal = new UsuarioPrincipal(usuario, HASH_FICTICIO);

        assertFalse(principal.isEnabled());
        assertIdentidade(principal, PerfilUsuario.COLABORADOR);
    }

    @Test
    void eraseCredentialsRemoveSomenteCredencialEPodeSerRepetido() {
        UsuarioPrincipal principal = new UsuarioPrincipal(usuario(PerfilUsuario.GESTOR), HASH_FICTICIO);
        List<GrantedAuthority> authorities = List.copyOf(principal.getAuthorities());
        assertTrue(HASH_FICTICIO.equals(principal.getPassword()), "Credencial de teste deve estar disponivel internamente");

        principal.eraseCredentials();
        principal.eraseCredentials();

        assertNull(principal.getPassword());
        assertIdentidade(principal, PerfilUsuario.GESTOR);
        assertTrue(principal.isEnabled());
        assertEquals(authorities, principal.getAuthorities());
    }

    @Test
    void serializacaoJacksonNaoExpoeCredencialMesmoAntesDeEraseCredentials() throws Exception {
        UsuarioPrincipal principal = new UsuarioPrincipal(usuario(PerfilUsuario.COLABORADOR), HASH_FICTICIO);
        ObjectMapper mapper = new ObjectMapper();

        String conteudo = mapper.writeValueAsString(principal);
        JsonNode json = mapper.readTree(conteudo);

        assertFalse(json.has("password"), "O getter de UserDetails nao deve expor a credencial no JSON");
        assertFalse(json.has("senhaHash"), "O campo interno nao deve expor a credencial no JSON");
        assertFalse(conteudo.contains(HASH_FICTICIO), "O JSON nao deve conter a credencial de teste");
        assertEquals(USUARIO_ID.toString(), json.path("usuarioId").asText());
        assertEquals(NOME, json.path("nome").asText());
        assertEquals(EMAIL, json.path("email").asText());
        assertEquals("COLABORADOR", json.path("perfilAcesso").asText());
    }

    @Test
    void toStringNaoExpoeCredencialNemDadosDaIdentidade() {
        UsuarioPrincipal principal = new UsuarioPrincipal(usuario(PerfilUsuario.COLABORADOR), HASH_FICTICIO);

        String descricao = principal.toString();

        assertFalse(descricao.contains(HASH_FICTICIO), "toString nao deve conter a credencial de teste");
        assertFalse(descricao.contains(USUARIO_ID.toString()));
        assertFalse(descricao.contains(NOME));
        assertFalse(descricao.contains(EMAIL));
    }

    @Test
    void serializacaoJavaPreservaIdentidadeMasNaoPersisteCredencialTransient() throws Exception {
        UsuarioPrincipal original = new UsuarioPrincipal(usuario(PerfilUsuario.RH_ADMIN), HASH_FICTICIO);
        byte[] serializado;
        try (ByteArrayOutputStream buffer = new ByteArrayOutputStream();
             ObjectOutputStream output = new ObjectOutputStream(buffer)) {
            output.writeObject(original);
            output.flush();
            serializado = buffer.toByteArray();
        }

        UsuarioPrincipal restaurado;
        try (ObjectInputStream input = new ObjectInputStream(new ByteArrayInputStream(serializado))) {
            restaurado = (UsuarioPrincipal) input.readObject();
        }

        assertNull(restaurado.getPassword());
        assertIdentidade(restaurado, PerfilUsuario.RH_ADMIN);
        assertTrue(restaurado.isEnabled());
        assertEquals(original.getAuthorities(), restaurado.getAuthorities());
        assertTrue(HASH_FICTICIO.equals(original.getPassword()), "Serializar nao deve alterar a instancia original");
    }

    @Test
    void identidadeSemCredencialLocalPreservaDadosSemInventarSenha() {
        UsuarioPrincipal principal = new UsuarioPrincipal(usuario(PerfilUsuario.GESTOR));

        assertNull(principal.getPassword());
        assertIdentidade(principal, PerfilUsuario.GESTOR);
        assertTrue(principal.isEnabled());
    }

    @Test
    void rejeitaEntidadeSemUuidAntesDeConstruirIdentidade() {
        Usuario semId = usuario(PerfilUsuario.COLABORADOR);
        ReflectionTestUtils.setField(semId, "id", null);

        assertThrows(NullPointerException.class, () -> new UsuarioPrincipal(semId, HASH_FICTICIO));
    }

    private static Usuario usuario(PerfilUsuario perfil) {
        Usuario usuario = new Usuario();
        ReflectionTestUtils.setField(usuario, "id", USUARIO_ID);
        usuario.setNome(NOME);
        usuario.setEmail(EMAIL);
        usuario.setPerfilAcesso(perfil);
        usuario.setAtivo(true);
        return usuario;
    }

    private static void assertIdentidade(UsuarioPrincipal principal, PerfilUsuario perfil) {
        assertAll(
                () -> assertEquals(USUARIO_ID, principal.getUsuarioId()),
                () -> assertEquals(NOME, principal.getNome()),
                () -> assertEquals(EMAIL, principal.getEmail()),
                () -> assertEquals(EMAIL, principal.getUsername()),
                () -> assertEquals(perfil, principal.getPerfilAcesso()));
    }
}
