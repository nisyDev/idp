package com.empresa.pdi.exception;

public class PdiRascunhoNaoEncontradoException extends RuntimeException {
    public PdiRascunhoNaoEncontradoException() {
        super("Rascunho não encontrado");
    }
}
