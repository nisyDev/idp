package com.empresa.pdi.dto;

import java.time.LocalDate;

/** Draft content only. Ownership, authorship and lifecycle values are server-controlled. */
public record CriarPdiRequest(String titulo, String objetivo,
                              LocalDate inicioPlanejado, LocalDate fimPlanejado) {
}
