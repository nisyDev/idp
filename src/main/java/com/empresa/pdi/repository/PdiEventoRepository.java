package com.empresa.pdi.repository;

import com.empresa.pdi.model.PdiEvento;
import org.springframework.data.repository.Repository;

/** Recording only. Historical deletion and unrestricted listing are not exposed. */
public interface PdiEventoRepository extends Repository<PdiEvento, Long> {
    <S extends PdiEvento> S saveAndFlush(S evento);
}
