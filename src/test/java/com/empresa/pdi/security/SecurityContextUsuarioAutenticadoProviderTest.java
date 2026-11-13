package com.empresa.pdi.security;

import com.empresa.pdi.enums.PerfilUsuario;
import com.empresa.pdi.model.Usuario;
import com.empresa.pdi.repository.UsuarioRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Unit tests only: no Spring context, datasource or authentication endpoint. */
@ExtendWith(MockitoExtension.class)
class SecurityContextUsuarioAutenticadoProviderTest {
    private static final UUID USUARIO_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
    // Deliberately not a BCrypt hash. No password verification occurs in this unit.
    private static final String HASH_FICTICIO = "hash-ficticio-apenas-para-testes-unitarios";

    @Mock UsuarioRepository usuarios;

    private SecurityContextUsuarioAutenticadoProvider provider;

    @BeforeEach
    void configurar() {
        SecurityContextHolder.clearContext();
        provider = new SecurityContextUsuarioAutenticadoProvider(usuarios);
    }

    @AfterEach
    void limparContexto() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void reconhecePrincipalDemoEConsultaSeuUuid() {
        Usuario usuario = usuario(PerfilUsuario.COLABORADOR);
        autenticar(principal(TipoPrincipal.DEMO, usuario));
        when(usuarios.findById(USUARIO_ID)).thenReturn(Optional.of(usuario));

        assertEquals(USUARIO_ID, provider.obterIdUsuarioAutenticado());
        verificarConsultaUnica();
    }

    @ParameterizedTest
    @EnumSource(PerfilUsuario.class)
    void reconhecePrincipalInternoComCredenciaisApagadasSemPrivilegioPorPerfil(PerfilUsuario perfil) {
        Usuario usuario = usuario(perfil);
        UsuarioPrincipal principal = new UsuarioPrincipal(usuario, HASH_FICTICIO);
        principal.eraseCredentials();
        autenticar(principal);
        when(usuarios.findById(USUARIO_ID)).thenReturn(Optional.of(usuario));

        assertEquals(USUARIO_ID, provider.obterIdUsuarioAutenticado());
        verificarConsultaUnica();
    }

    @Test
    void rejeitaAutenticacaoAusenteSemConsultarRepositorio() {
        assertAutenticacaoNecessaria();
        verifyNoInteractions(usuarios);
    }

    @ParameterizedTest
    @EnumSource(TipoPrincipal.class)
    void rejeitaTokenNaoAutenticadoMesmoComPrincipalConhecido(TipoPrincipal tipo) {
        UserDetails principal = principal(tipo, usuario(PerfilUsuario.COLABORADOR));
        definirAutenticacao(UsernamePasswordAuthenticationToken.unauthenticated(principal, null));

        assertAutenticacaoNecessaria();
        verifyNoInteractions(usuarios);
    }

    @ParameterizedTest
    @EnumSource(TipoPrincipal.class)
    void rejeitaTokenAnonimoMesmoMarcadoComoAutenticadoEComPrincipalConhecido(TipoPrincipal tipo) {
        UserDetails principal = principal(tipo, usuario(PerfilUsuario.COLABORADOR));
        var authentication = new AnonymousAuthenticationToken("chave-unitaria", principal,
                principal.getAuthorities());
        assertTrue(authentication.isAuthenticated());
        definirAutenticacao(authentication);

        assertAutenticacaoNecessaria();
        verifyNoInteractions(usuarios);
    }

    @Test
    void rejeitaUuidTextualComoPrincipalMesmoComAuthority() {
        definirAutenticacao(UsernamePasswordAuthenticationToken.authenticated(USUARIO_ID.toString(), null,
                List.of(new SimpleGrantedAuthority("ROLE_RH_ADMIN"))));

        assertAutenticacaoNecessaria();
        verifyNoInteractions(usuarios);
    }

    @Test
    void rejeitaPrincipalAusenteMesmoComAutenticacaoConfirmada() {
        Authentication authentication = mock(Authentication.class);
        when(authentication.isAuthenticated()).thenReturn(true);
        when(authentication.getPrincipal()).thenReturn(null);
        definirAutenticacao(authentication);

        assertAutenticacaoNecessaria();
        verifyNoInteractions(usuarios);
    }

    @Test
    void rejeitaUuidNuloDoPrincipalDemoAntesDeConsultarRepositorio() {
        autenticar(new DemoUsuarioPrincipal("demo-unitario", HASH_FICTICIO, null));

        assertCredencialInvalida();
        verifyNoInteractions(usuarios);
    }

    @ParameterizedTest
    @EnumSource(TipoPrincipal.class)
    void rejeitaUsuarioInexistenteMesmoComPrincipalAutenticado(TipoPrincipal tipo) {
        autenticar(principal(tipo, usuario(PerfilUsuario.COLABORADOR)));
        when(usuarios.findById(USUARIO_ID)).thenReturn(Optional.empty());

        assertCredencialInvalida();
        verificarConsultaUnica();
    }

    @ParameterizedTest
    @EnumSource(TipoPrincipal.class)
    void rejeitaUsuarioInativadoDepoisDaAutenticacao(TipoPrincipal tipo) {
        Usuario usuario = usuario(PerfilUsuario.COLABORADOR);
        UserDetails principal = principal(tipo, usuario);
        autenticar(principal);
        usuario.setAtivo(false);
        assertTrue(principal.isEnabled());
        when(usuarios.findById(USUARIO_ID)).thenReturn(Optional.of(usuario));

        assertCredencialInvalida();
        verificarConsultaUnica();
    }

    private void assertAutenticacaoNecessaria() {
        var failure = assertThrows(AuthenticationCredentialsNotFoundException.class,
                provider::obterIdUsuarioAutenticado);
        assertEquals("Autenticação necessária", failure.getMessage());
    }

    private void assertCredencialInvalida() {
        var failure = assertThrows(BadCredentialsException.class, provider::obterIdUsuarioAutenticado);
        assertEquals("Autenticação inválida", failure.getMessage());
    }

    private void verificarConsultaUnica() {
        verify(usuarios).findById(USUARIO_ID);
        verifyNoMoreInteractions(usuarios);
    }

    private static void autenticar(UserDetails principal) {
        definirAutenticacao(UsernamePasswordAuthenticationToken.authenticated(principal, null,
                principal.getAuthorities()));
    }

    private static void definirAutenticacao(Authentication authentication) {
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
    }

    private static UserDetails principal(TipoPrincipal tipo, Usuario usuario) {
        return switch (tipo) {
            case DEMO -> new DemoUsuarioPrincipal("demo-unitario", HASH_FICTICIO, usuario.getId());
            case INTERNO -> new UsuarioPrincipal(usuario, HASH_FICTICIO);
        };
    }

    private static Usuario usuario(PerfilUsuario perfil) {
        Usuario usuario = new Usuario();
        ReflectionTestUtils.setField(usuario, "id", USUARIO_ID);
        usuario.setNome("Usuário unitário");
        usuario.setEmail("usuario@example.test");
        usuario.setPerfilAcesso(perfil);
        usuario.setAtivo(true);
        return usuario;
    }

    enum TipoPrincipal { DEMO, INTERNO }
}
