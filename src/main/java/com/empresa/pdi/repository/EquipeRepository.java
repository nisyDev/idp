package com.empresa.pdi.repository;

import com.empresa.pdi.model.Equipe;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;
import java.util.Optional;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface EquipeRepository extends JpaRepository<Equipe, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select e from Equipe e where e.id = :id")
    Optional<Equipe> findByIdForUpdate(@Param("id") UUID id);
}
