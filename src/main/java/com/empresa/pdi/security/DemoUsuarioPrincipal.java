package com.empresa.pdi.security;

import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;
import java.util.List;
import java.util.UUID;

/** UUID originates exclusively from server configuration, never from HTTP input. */
final class DemoUsuarioPrincipal extends User {
    private final UUID usuarioId;

    DemoUsuarioPrincipal(String login, String hash, UUID usuarioId) {
        super(login, hash, List.of(new SimpleGrantedAuthority("ROLE_DEMO")));
        this.usuarioId = usuarioId;
    }

    UUID usuarioId() {
        return usuarioId;
    }

    @Override
    public String toString() {
        return "DemoUsuarioPrincipal [redacted]";
    }
}
