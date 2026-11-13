package com.empresa.pdi.repository;

import com.empresa.pdi.model.HistoricoEquipe;
import org.springframework.data.repository.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Exposes recording and reading only; history has no deletion operations. */
public interface HistoricoEquipeRepository extends Repository<HistoricoEquipe, UUID> {
    <S extends HistoricoEquipe> S save(S historico);

    Optional<HistoricoEquipe> findById(UUID id);

    List<HistoricoEquipe> findAll();
}
