package com.empresa.pdi.repository;

import com.empresa.pdi.model.PDI;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PDIRepository extends JpaRepository<PDI, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from PDI p where p.id = :id and p.usuario.id = :titular")
    Optional<PDI> buscarDoTitularParaAtualizacao(@Param("id") Long id, @Param("titular") UUID titular);

    // Busca todos os PDIs de um usuário específico
    // O Spring gera o SQL automaticamente a partir do nome do método
    // É como dizer "traga todos os pedidos desse cliente"
    List<PDI> findByUsuarioId(UUID usuarioId);
}
