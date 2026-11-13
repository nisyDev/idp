package com.empresa.pdi.controller;

import com.empresa.pdi.exception.UsuarioNaoEncontradoException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Scoped to user queries; does not change the error contracts of other modules. */
@RestControllerAdvice(assignableTypes = UsuarioController.class)
public class UsuarioConsultaExceptionHandler {
    @ExceptionHandler(UsuarioNaoEncontradoException.class)
    public ProblemDetail usuarioNaoEncontrado(UsuarioNaoEncontradoException exception) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "Usuário não encontrado");
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail falhaNaConsulta(Exception exception) {
        // Do not send SQL, database details or internal exception messages to clients.
        return ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR,
                "Não foi possível consultar os usuários");
    }
}
