package com.empresa.pdi.dto;

import com.empresa.pdi.enums.PerfilUsuario;
import com.empresa.pdi.model.Usuario;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.Set;
import java.util.HashSet;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class UsuarioResponseTest {
    @Test
    void mapeamentoNaoAcessaAssociacoesNemDadosInternos() {
        Usuario usuario = mock(Usuario.class);
        UUID id = UUID.randomUUID();
        when(usuario.getId()).thenReturn(id);
        when(usuario.getNome()).thenReturn("Nome");
        when(usuario.getEmail()).thenReturn("email");
        when(usuario.getPerfilAcesso()).thenReturn(PerfilUsuario.COLABORADOR);

        assertEquals(new UsuarioResponse(id, "Nome", "email", PerfilUsuario.COLABORADOR),
                UsuarioResponse.from(usuario));
        verify(usuario).getId();
        verify(usuario).getNome();
        verify(usuario).getEmail();
        verify(usuario).getPerfilAcesso();
        verifyNoMoreInteractions(usuario);
    }

    @ParameterizedTest
    @EnumSource(PerfilUsuario.class)
    void jsonPreservaCamposPublicosEValoresAprovados(PerfilUsuario perfil) throws Exception {
        UUID id = UUID.randomUUID();
        var resposta = new UsuarioResponse(id, "Nome", "email", perfil);
        var json = new ObjectMapper().valueToTree(resposta);
        Set<String> campos = new HashSet<>();
        json.fieldNames().forEachRemaining(campos::add);
        assertEquals(Set.of("id", "nome", "email", "perfil"), campos);
        assertEquals(id.toString(), json.get("id").asText());
        assertEquals("Nome", json.get("nome").asText());
        assertEquals("email", json.get("email").asText());
        assertEquals(perfil.name(), json.get("perfil").asText());
    }

    @Test
    void dtoNaoDependeDeEntidadeAposMapeamento() {
        Usuario usuario = new Usuario();
        usuario.setNome("Original");
        UsuarioResponse resposta = UsuarioResponse.from(usuario);
        usuario.setNome("Alterado");
        assertEquals("Original", resposta.nome());
    }
}
