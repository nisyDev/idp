package com.empresa.pdi.security;

import java.util.UUID;

/**
 * Internal port for the future authentication adapter.
 * Implementations must obtain the user ID from a verified server-side authentication
 * context, never from request data. The demo-local adapter uses Spring Security.
 * Missing authentication must fail, never select a default or fictitious user.
 */
@FunctionalInterface
public interface UsuarioAutenticadoProvider {
    UUID obterIdUsuarioAutenticado();
}
