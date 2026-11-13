package com.empresa.pdi.security;

import com.empresa.pdi.model.Usuario;
import com.empresa.pdi.repository.UsuarioCredencialLocalRepository;
import com.empresa.pdi.repository.UsuarioRepository;
import java.util.Objects;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.transaction.annotation.Transactional;

/** Not registered as a bean. Activation requires a future explicit security configuration. */
public class LocalUserDetailsService implements UserDetailsService {
    private final UsuarioRepository usuarios;
    private final UsuarioCredencialLocalRepository credenciais;

    public LocalUserDetailsService(UsuarioRepository usuarios,
                                   UsuarioCredencialLocalRepository credenciais) {
        this.usuarios = Objects.requireNonNull(usuarios, "Repositório de usuários obrigatório");
        this.credenciais = Objects.requireNonNull(credenciais, "Repositório de credenciais obrigatório");
    }

    @Override
    @Transactional(readOnly = true)
    public UsuarioPrincipal loadUserByUsername(String email) throws UsernameNotFoundException {
        if (email == null || email.isBlank()) {
            throw autenticacaoInvalida();
        }
        Usuario usuario = usuarios.findByEmailIgnoreCase(email)
                .filter(Usuario::isAtivo)
                .orElseThrow(LocalUserDetailsService::autenticacaoInvalida);
        var credencial = credenciais.findById(usuario.getId())
                .filter(valor -> valor.getSenhaHash() != null && !valor.getSenhaHash().isBlank())
                .orElseThrow(LocalUserDetailsService::autenticacaoInvalida);
        return new UsuarioPrincipal(usuario, credencial.getSenhaHash());
    }

    private static UsernameNotFoundException autenticacaoInvalida() {
        return new UsernameNotFoundException("Autenticação inválida");
    }
}
