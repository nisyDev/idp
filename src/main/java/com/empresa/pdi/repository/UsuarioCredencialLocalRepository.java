package com.empresa.pdi.repository;

import com.empresa.pdi.model.UsuarioCredencialLocal;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UsuarioCredencialLocalRepository extends JpaRepository<UsuarioCredencialLocal, UUID> {
}
