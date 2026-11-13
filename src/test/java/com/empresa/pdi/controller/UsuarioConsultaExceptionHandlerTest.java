package com.empresa.pdi.controller;

import com.empresa.pdi.exception.UsuarioNaoEncontradoException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

import static org.junit.jupiter.api.Assertions.*;

class UsuarioConsultaExceptionHandlerTest {
    private final UsuarioConsultaExceptionHandler handler = new UsuarioConsultaExceptionHandler();

    @Test
    void usuarioInexistenteCorrespondeANotFound() {
        var resposta = handler.usuarioNaoEncontrado(new UsuarioNaoEncontradoException());
        assertEquals(404, resposta.getStatus());
        assertEquals("Usuário não encontrado", resposta.getDetail());
    }

    @Test
    void falhaDePersistenciaNaoExpoeDetalhesInternos() throws Exception {
        var resposta = handler.falhaNaConsulta(new DataAccessResourceFailureException("detalhe interno"));
        assertEquals(500, resposta.getStatus());
        assertEquals("Não foi possível consultar os usuários", resposta.getDetail());
        String json = new ObjectMapper().writeValueAsString(resposta);
        assertFalse(json.contains("detalhe interno"));
        assertFalse(json.contains("stackTrace"));
        assertFalse(json.contains("DataAccessResourceFailureException"));
    }

    @Test
    void erroInesperadoTambemTemRespostaGenerica() {
        var resposta = handler.falhaNaConsulta(new IllegalStateException("detalhe interno"));
        assertEquals(500, resposta.getStatus());
        assertEquals("Não foi possível consultar os usuários", resposta.getDetail());
    }
}
