package com.empresa.pdi.dto;

import com.empresa.pdi.enums.NivelCargo;
import com.empresa.pdi.model.Cargo;
import java.util.UUID;

/** Scalar-only catalog response; no persistence graph is exposed. */
public record CargoResponse(UUID id, String nome, String descricao, NivelCargo nivel, boolean ativo) {
    public static CargoResponse from(Cargo cargo) {
        return new CargoResponse(cargo.getId(), cargo.getNome(), cargo.getDescricao(),
                cargo.getNivel(), cargo.isAtivo());
    }
}
