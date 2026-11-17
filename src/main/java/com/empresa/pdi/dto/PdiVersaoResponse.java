package com.empresa.pdi.dto;

import com.empresa.pdi.enums.StatusAprovacaoPdi;
import com.empresa.pdi.enums.StatusExecucaoPdi;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record PdiVersaoResponse(
        Long id, Long versaoId, Integer numero, UUID titularId, UUID gestorAtualId,
        Long versaoVigenteId, StatusExecucaoPdi statusExecucao, StatusAprovacaoPdi statusAprovacao,
        String titulo, String objetivo, LocalDate inicioPlanejado, LocalDate fimPlanejado,
        Instant criadoEm, Instant criadaVersaoEm, Instant submetidaEm, UUID gestorNaSubmissaoId,
        Instant decididaEm, UUID decididoPorId, String justificativaDecisao) {}
