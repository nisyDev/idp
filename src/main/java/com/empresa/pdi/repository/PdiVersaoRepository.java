package com.empresa.pdi.repository;

import com.empresa.pdi.model.PdiVersao;
import org.springframework.data.repository.Repository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Lock;
import jakarta.persistence.LockModeType;
import org.springframework.data.repository.query.Param;
import com.empresa.pdi.enums.StatusAprovacaoPdi;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Minimal write contract for the initial draft; no history deletion operations. */
public interface PdiVersaoRepository extends Repository<PdiVersao, Long> {
    <S extends PdiVersao> S saveAndFlush(S versao);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select v from PdiVersao v where v.pdi.id = :pdiId and v.numero = 1")
    Optional<PdiVersao> buscarInicialParaAtualizacao(@Param("pdiId") Long pdiId);

    @Query("select count(v) from PdiVersao v where v.pdi.id = :pdiId and v.submetidaEm is not null")
    long contarVersoesSubmetidas(@Param("pdiId") Long pdiId);

    // Submitted versions are never exposed across PDI owners, including after a decision.
    @Query("select v from PdiVersao v join fetch v.pdi p left join fetch v.gestorNaSubmissao "
            + "where p.usuario.id = :titular and v.submetidaEm is not null "
            + "order by v.submetidaEm desc, v.id desc")
    List<PdiVersao> listarSubmetidos(@Param("titular") UUID titular);

    @Query("select v from PdiVersao v join fetch v.pdi p where p.usuario.id = :titular "
            + "and v.statusAprovacao = :status and v.submetidaEm is null order by p.criadoEm desc, p.id desc")
    List<PdiVersao> listarRascunhos(@Param("titular") UUID titular,
                                   @Param("status") StatusAprovacaoPdi status);

    @Query("select v from PdiVersao v join fetch v.pdi p where p.id = :pdiId and p.usuario.id = :titular "
            + "and v.statusAprovacao = :status and v.submetidaEm is null")
    Optional<PdiVersao> buscarRascunho(@Param("pdiId") Long pdiId, @Param("titular") UUID titular,
                                      @Param("status") StatusAprovacaoPdi status);
}
