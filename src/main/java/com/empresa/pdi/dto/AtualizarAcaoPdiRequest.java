package com.empresa.pdi.dto;

import java.time.LocalDate;

/** Full replacement of editable action content; null values remain permitted. */
public record AtualizarAcaoPdiRequest(String titulo, String objetivo,
                                     LocalDate inicioPlanejado, LocalDate fimPlanejado,
                                     String resultadoEsperado) {
}
