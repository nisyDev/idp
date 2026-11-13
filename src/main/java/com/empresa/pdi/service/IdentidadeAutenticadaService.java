package com.empresa.pdi.service;

import com.empresa.pdi.dto.UsuarioResponse;
import com.empresa.pdi.model.Usuario;
import com.empresa.pdi.repository.UsuarioRepository;
import com.empresa.pdi.security.UsuarioAutenticadoProvider;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Current account data, resolved from the verified identity rather than session copies. */
@Service
@Profile("auth-local")
public class IdentidadeAutenticadaService {
    private final UsuarioAutenticadoProvider autenticacao;
    private final UsuarioRepository usuarios;

    public IdentidadeAutenticadaService(UsuarioAutenticadoProvider autenticacao, UsuarioRepository usuarios) {
        this.autenticacao = autenticacao;
        this.usuarios = usuarios;
    }

    @Transactional(readOnly = true)
    public UsuarioResponse consultar() {
        UUID usuarioId = autenticacao.obterIdUsuarioAutenticado();
        if (usuarioId == null) {
            throw new BadCredentialsException("Autenticação inválida");
        }
        return usuarios.findById(usuarioId)
                .filter(Usuario::isAtivo)
                .map(UsuarioResponse::from)
                .orElseThrow(() -> new BadCredentialsException("Autenticação inválida"));
    }
}
