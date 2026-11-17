package com.empresa.pdi.controller;

import com.empresa.pdi.exception.PdiRascunhoNaoEncontradoException;
import com.empresa.pdi.exception.PdiRascunhoNaoEditavelException;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.security.access.AccessDeniedException;

@RestControllerAdvice(assignableTypes = {PDIController.class, AcaoPDIController.class})
public class PdiExceptionHandler {
    @ExceptionHandler(ResponseStatusException.class)
    public ProblemDetail regraNegocio(ResponseStatusException erro) {
        // Never return exception causes or arbitrary messages from persistence layers.
        String detalhe = switch (erro.getStatusCode().value()) {
            case 400 -> "Dados do PDI inválidos";
            case 403 -> "Operação não autorizada";
            case 404 -> "PDI ou versão não encontrado";
            case 409 -> "Operação incompatível com o estado atual; consulte novamente o PDI";
            default -> "Não foi possível processar o PDI";
        };
        return ProblemDetail.forStatusAndDetail(erro.getStatusCode(), detalhe);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ProblemDetail acessoNegado() {
        return ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, "Operação não autorizada");
    }
    @ExceptionHandler({PdiRascunhoNaoEditavelException.class, ConcurrencyFailureException.class})
    public ProblemDetail conflito() {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
                "Não foi possível editar o rascunho no estado atual; consulte novamente o PDI");
    }

    @ExceptionHandler(PdiRascunhoNaoEncontradoException.class)
    public ProblemDetail naoEncontrado() {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "Rascunho não encontrado");
    }

    @ExceptionHandler({IllegalArgumentException.class, HttpMessageNotReadableException.class,
            MethodArgumentTypeMismatchException.class})
    public ProblemDetail dadosInvalidos() {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Dados do rascunho inválidos");
    }

    @ExceptionHandler(AuthenticationException.class)
    public ProblemDetail autenticacao() {
        return ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, "Autenticação necessária");
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail falhaInterna() {
        return ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, "Não foi possível processar o rascunho");
    }
}
