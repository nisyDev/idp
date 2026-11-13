package com.empresa.pdi.dto;

import com.empresa.pdi.enums.PerfilUsuario;
import com.empresa.pdi.model.Usuario;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.UUID;

/** Scalar-only response; retains the existing JSON property "perfil". */
public record UsuarioResponse(UUID id, String nome, String email,
                              @JsonProperty("perfil") PerfilUsuario perfilAcesso) {
    public static UsuarioResponse from(Usuario usuario) {
        return new UsuarioResponse(usuario.getId(), usuario.getNome(), usuario.getEmail(),
                usuario.getPerfilAcesso());
    }
}
