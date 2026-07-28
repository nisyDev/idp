package com.corp.idp.repository;

import com.corp.idp.model.PDI;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;

public interface PdiRepository extends JpaRepository<PDI, UUID> {
}