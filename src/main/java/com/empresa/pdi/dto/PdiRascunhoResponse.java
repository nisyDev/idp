package com.empresa.pdi.dto;

import com.empresa.pdi.enums.StatusAprovacaoPdi;
import com.empresa.pdi.enums.StatusExecucaoPdi;
import java.time.Instant;
import java.time.LocalDate;

/** Scalar-only representation; draft version and approved-current version are distinct. */
public record PdiRascunhoResponse(Long id, Long versaoId, Integer numeroVersao,
                                  Long versaoVigenteId, StatusExecucaoPdi statusExecucao,
                                  StatusAprovacaoPdi statusAprovacao,
                                  String titulo, String objetivo,
                                  LocalDate inicioPlanejado, LocalDate fimPlanejado,
                                  Instant criadoEm, Instant versaoCriadaEm) {
}
