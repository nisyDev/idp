package com.corp.idp.repository;

import com.corp.idp.model.AcaoPDI;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;

public interface AcaoPdiRepository extends JpaRepository<AcaoPDI, UUID> {
}