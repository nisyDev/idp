package com.empresa.pdi.repository;

import com.empresa.pdi.model.Cargo;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;
import java.util.Optional;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CargoRepository extends JpaRepository<Cargo, UUID> {
    List<Cargo> findByAtivoTrue();

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Cargo c where c.id = :id")
    Optional<Cargo> findByIdForUpdate(@Param("id") UUID id);
}
