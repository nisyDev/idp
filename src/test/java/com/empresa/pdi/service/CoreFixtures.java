package com.empresa.pdi.service;

import com.empresa.pdi.model.Cargo;
import com.empresa.pdi.model.Equipe;
import com.empresa.pdi.model.Usuario;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.UUID;

final class CoreFixtures {
    static final UUID USUARIO = new UUID(0, 10);
    static final UUID ORIGEM = new UUID(0, 2);
    static final UUID DESTINO = new UUID(0, 1);
    static final UUID CARGO = new UUID(0, 3);

    static Equipe equipe(UUID id) {
        Equipe equipe = new Equipe();
        ReflectionTestUtils.setField(equipe, "id", id);
        return equipe;
    }

    static Usuario usuario(Equipe equipe) {
        Usuario usuario = new Usuario();
        ReflectionTestUtils.setField(usuario, "id", USUARIO);
        usuario.setEquipeAtual(equipe);
        return usuario;
    }

    static Cargo cargo() {
        Cargo cargo = new Cargo();
        ReflectionTestUtils.setField(cargo, "id", CARGO);
        return cargo;
    }
}
