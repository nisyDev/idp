package com.empresa.pdi.dto;

/** The decision maker receives only the identity of the new private draft. */
public record PdiDecisaoResponse(PdiVersaoResponse versaoDecidida, Long novaVersaoId, Integer novoNumero) {}
