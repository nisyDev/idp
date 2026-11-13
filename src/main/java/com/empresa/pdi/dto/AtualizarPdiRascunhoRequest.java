package com.empresa.pdi.dto;

import java.time.LocalDate;

/** Full replacement of editable draft fields; null values remain permitted. */
public record AtualizarPdiRascunhoRequest(String titulo, String objetivo,
                                        LocalDate inicioPlanejado, LocalDate fimPlanejado) {
}
