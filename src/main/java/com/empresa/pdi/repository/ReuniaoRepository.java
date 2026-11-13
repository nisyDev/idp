package com.empresa.pdi.repository;

import com.empresa.pdi.model.Reuniao;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.UUID;

public interface ReuniaoRepository extends JpaRepository<Reuniao, Long> {
    List<Reuniao> findByColaboradorId(UUID colaboradorId);
}
