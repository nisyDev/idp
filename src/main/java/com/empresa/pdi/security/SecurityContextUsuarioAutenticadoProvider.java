package com.empresa.pdi.security;

import com.empresa.pdi.repository.UsuarioRepository;
import org.springframework.context.annotation.Profile;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import java.util.UUID;

@Component
@Profile({"demo-local", "auth-local"})
public class SecurityContextUsuarioAutenticadoProvider implements UsuarioAutenticadoProvider {
    private final UsuarioRepository usuarios;

    public SecurityContextUsuarioAutenticadoProvider(UsuarioRepository usuarios) {
        this.usuarios = usuarios;
    }

    @Override
    @Transactional(readOnly = true)
    public UUID obterIdUsuarioAutenticado() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) {
            throw new AuthenticationCredentialsNotFoundException("Autenticação necessária");
        }
        var principal = authentication.getPrincipal();
        UUID id;
        if (principal instanceof DemoUsuarioPrincipal demoPrincipal) {
            id = demoPrincipal.usuarioId();
        } else if (principal instanceof UsuarioPrincipal usuarioPrincipal) {
            id = usuarioPrincipal.getUsuarioId();
        } else {
            throw new AuthenticationCredentialsNotFoundException("Autenticação necessária");
        }
        if (id == null) {
            throw new BadCredentialsException("Autenticação inválida");
        }
        java.util.Optional<com.empresa.pdi.model.Usuario> user = usuarios.findById(id);
        boolean userActive = user.filter(usuario -> usuario.isAtivo()).isPresent();
        if (!userActive) {
            throw new BadCredentialsException("Autenticação inválida");
        }
        return id;
    }
}
