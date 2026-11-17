package com.empresa.pdi.repository;

import com.empresa.pdi.model.AcaoPdiVersao;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/** Draft content is always selected within its PDI and version. */
public interface AcaoPdiVersaoRepository extends Repository<AcaoPdiVersao, Long> {
    <S extends AcaoPdiVersao> S saveAndFlush(S versao);

    // Count only actions belonging to the exact proposal version being submitted.
    @Query("select count(v) from AcaoPdiVersao v where v.pdiId = :pdiId and v.pdiVersaoId = :versaoId")
    long contarNaVersao(@Param("pdiId") Long pdiId, @Param("versaoId") Long versaoId);

    @Query("select v from AcaoPdiVersao v join fetch v.acaoPdi "
            + "where v.pdiId = :pdiId and v.pdiVersaoId = :versaoId order by v.ordem, v.id")
    List<AcaoPdiVersao> listarNaVersao(@Param("pdiId") Long pdiId, @Param("versaoId") Long versaoId);

    @Query("select v from AcaoPdiVersao v join fetch v.acaoPdi "
            + "where v.pdiId = :pdiId and v.pdiVersaoId = :versaoId and v.acaoPdiId = :acaoId")
    Optional<AcaoPdiVersao> buscarNaVersao(@Param("pdiId") Long pdiId,
                                         @Param("versaoId") Long versaoId, @Param("acaoId") Long acaoId);
}
