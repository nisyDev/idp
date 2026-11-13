package com.empresa.pdi.controller;

import com.empresa.pdi.dto.UsuarioResponse;
import com.empresa.pdi.enums.PerfilUsuario;
import com.empresa.pdi.model.Usuario;
import com.empresa.pdi.service.UsuarioService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UsuarioControllerTest {
    @Mock UsuarioService service;

    @Test
    void listaUsuariosPeloServicoERetornaSomenteDtos() {
        UUID id = UUID.randomUUID();
        Usuario usuario = new Usuario("identidade externa");
        ReflectionTestUtils.setField(usuario, "id", id);
        usuario.setNome("Nome");
        usuario.setEmail("email");
        usuario.setPerfilAcesso(PerfilUsuario.GESTOR);
        when(service.listar()).thenReturn(List.of(usuario));

        assertEquals(List.of(new UsuarioResponse(id, "Nome", "email", PerfilUsuario.GESTOR)),
                new UsuarioController(service).listar());
        verify(service).listar();
        verifyNoMoreInteractions(service);
    }

    @Test
    void listaVaziaContinuaVaziaSemErroDeUsuarioInexistente() {
        when(service.listar()).thenReturn(List.of());
        assertEquals(List.of(), new UsuarioController(service).listar());
        verify(service).listar();
    }

    @Test
    void naoOcultaFalhaDaConsultaComoListaVazia() {
        var falha = new DataAccessResourceFailureException("detalhe interno");
        when(service.listar()).thenThrow(falha);
        assertSame(falha, assertThrows(DataAccessResourceFailureException.class,
                () -> new UsuarioController(service).listar()));
    }
}
