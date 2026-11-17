package com.empresa.pdi.dto;

import com.empresa.pdi.enums.StatusAprovacaoPdi;
import com.empresa.pdi.enums.StatusExecucaoPdi;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** Private owner-only view of a submitted PDI version; no JPA entities are serialized. */
public record PdiSubmetidoResponse(
        Long id, Long versaoId, Integer numero, Long versaoVigenteId,
        StatusExecucaoPdi statusExecucao, StatusAprovacaoPdi statusAprovacao,
        String titulo, String objetivo, LocalDate inicioPlanejado, LocalDate fimPlanejado,
        Instant criadoEm, Instant criadaVersaoEm, Instant submetidaEm,
        UUID gestorNaSubmissaoId) {
}
