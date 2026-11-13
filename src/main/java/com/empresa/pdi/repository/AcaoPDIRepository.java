package com.empresa.pdi.repository;

import com.empresa.pdi.model.AcaoPDI;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface AcaoPDIRepository extends JpaRepository<AcaoPDI, Long> {
    List<AcaoPDI> findByPdiId(Long pdiId);
}