package com.empresa.pdi.exception;

public class PdiRascunhoNaoEditavelException extends RuntimeException {
    public PdiRascunhoNaoEditavelException() {
        super("O PDI não está disponível para edição de rascunho inicial");
    }
}
