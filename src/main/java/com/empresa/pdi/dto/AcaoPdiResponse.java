package com.empresa.pdi.dto;

import com.empresa.pdi.enums.StatusExecucaoAcao;
import java.time.LocalDate;

/** Scalar-only view of an action in the selected draft version. */
public record AcaoPdiResponse(Long id, Long acaoVersaoId, Long pdiId, Long pdiVersaoId,
                              String titulo, String objetivo, String resultadoEsperado,
                              LocalDate inicioPlanejado, LocalDate fimPlanejado,
                              Integer ordem, StatusExecucaoAcao statusExecucao) {
}
