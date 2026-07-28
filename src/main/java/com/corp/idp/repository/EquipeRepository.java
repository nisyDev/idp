package com.corp.idp.repository;

import com.corp.idp.model.Equipe;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;

public interface EquipeRepository extends JpaRepository<Equipe, UUID> {
}