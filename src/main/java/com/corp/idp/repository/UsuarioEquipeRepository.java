package com.corp.idp.repository;

import com.corp.idp.model.UsuarioEquipe;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;

public interface UsuarioEquipeRepository extends JpaRepository<UsuarioEquipe, UUID> {
}