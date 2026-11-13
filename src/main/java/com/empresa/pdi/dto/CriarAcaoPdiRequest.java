package com.empresa.pdi.dto;

import java.time.LocalDate;

/** Editable draft content only; identity and lifecycle are server-controlled. */
public record CriarAcaoPdiRequest(String titulo, String objetivo, String resultadoEsperado,
                                  LocalDate inicioPlanejado, LocalDate fimPlanejado) {
}
