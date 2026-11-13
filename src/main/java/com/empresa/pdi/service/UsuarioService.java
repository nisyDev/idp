package com.empresa.pdi.service;

import com.empresa.pdi.enums.PerfilUsuario;
import com.empresa.pdi.exception.UsuarioNaoEncontradoException;
import com.empresa.pdi.model.*;
import com.empresa.pdi.repository.*;
import com.empresa.pdi.security.UsuarioAutenticadoProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import static com.empresa.pdi.service.CoreValidacao.*;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class UsuarioService {
    private final UsuarioRepository usuarios;
    private final CargoRepository cargos;
    private final EquipeRepository equipes;
    private final HistoricoEquipeRepository historicos;
    private final ObjectProvider<UsuarioAutenticadoProvider> autenticacao;

    public List<Usuario> listar() {
        return usuarios.findAll();
    }

    public Usuario buscar(UUID id) {
        return usuarios.findById(obrigatorio(id, "id"))
                .orElseThrow(UsuarioNaoEncontradoException::new);
    }

    /** The responsible user is resolved internally, never accepted as caller input. */
    @Transactional
    public Usuario criarManual(String adObjectId, String nome, String email, PerfilUsuario perfil,
                               UUID cargoId, UUID equipeId) {
        UsuarioAutenticadoProvider provider = autenticacao.getIfAvailable();
        if (provider == null) {
            throw new IllegalStateException("Cadastro manual requer integração de autenticação");
        }
        UUID responsavelId = provider.obterIdUsuarioAutenticado();
        if (responsavelId == null) {
            throw new IllegalStateException("Cadastro manual requer usuário autenticado");
        }
        Usuario responsavel = usuarios.findById(responsavelId)
                .orElseThrow(() -> new IllegalStateException("Usuário autenticado não está cadastrado"));
        return criarComHistorico(adObjectId, nome, email, perfil, cargoId, equipeId, responsavel);
    }

    /** Internal automation entry point, not a frontend-selectable authentication bypass. */
    @Transactional
    public Usuario criarAutomatico(String adObjectId, String nome, String email, PerfilUsuario perfil,
                                   UUID cargoId, UUID equipeId) {
        return criarComHistorico(adObjectId, nome, email, perfil, cargoId, equipeId, null);
    }

    /** Internal import entry point; the initial history has no human responsible user. */
    @Transactional
    public Usuario criarPorImportacao(String adObjectId, String nome, String email, PerfilUsuario perfil,
                                      UUID cargoId, UUID equipeId) {
        return criarComHistorico(adObjectId, nome, email, perfil, cargoId, equipeId, null);
    }

    private Usuario criarComHistorico(String adObjectId, String nome, String email, PerfilUsuario perfil,
                                      UUID cargoId, UUID equipeId, Usuario responsavel) {
        validarDados(nome, email, perfil);
        obrigatorio(equipeId, "equipeId");
        Cargo cargo = cargos.findByIdForUpdate(obrigatorio(cargoId, "cargoId"))
                .orElseThrow(() -> new IllegalArgumentException("Cargo não encontrado"));
        exigir(cargo.isAtivo(), "Cargo inativo não recebe novas atribuições");
        Equipe equipe = equipes.findByIdForUpdate(equipeId)
                .orElseThrow(() -> new IllegalArgumentException("Equipe não encontrada"));
        exigir(equipe.isAtivo(), "Equipe inativa não recebe novas atribuições");
        Usuario usuario = new Usuario(adObjectId);
        usuario.setNome(nome);
        usuario.setEmail(email);
        usuario.setPerfilAcesso(perfil);
        usuario.setCargo(cargo);
        usuario.setEquipeAtual(equipe);
        // Persist before the history references the new user's generated UUID.
        usuarios.saveAndFlush(usuario);
        historicos.save(new HistoricoEquipe(usuario, null, equipe, Instant.now(), responsavel, null));
        return usuario;
    }

    @Transactional
    public Usuario atualizarDados(UUID id, String nome, String email, PerfilUsuario perfil) {
        validarDados(nome, email, perfil);
        Usuario usuario = bloquear(id);
        usuario.setNome(nome);
        usuario.setEmail(email);
        usuario.setPerfilAcesso(perfil);
        return usuarios.save(usuario);
    }

    @Transactional
    public void alterarCargo(UUID usuarioId, UUID cargoId) {
        obrigatorio(cargoId, "cargoId");
        Usuario usuario = bloquear(usuarioId);
        // Existing links to inactive positions are preserved.
        if (usuario.getCargo() != null && Objects.equals(usuario.getCargo().getId(), cargoId)) {
            return;
        }
        Cargo cargo = cargos.findByIdForUpdate(cargoId)
                .orElseThrow(() -> new IllegalArgumentException("Cargo não encontrado"));
        exigir(cargo.isAtivo(), "Cargo inativo não recebe novas atribuições");
        usuario.setCargo(cargo);
        usuarios.save(usuario);
    }

    @Transactional
    public void inativar(UUID id) {
        Usuario usuario = bloquear(id);
        Equipe equipe = equipes.findByIdForUpdate(usuario.getEquipeAtual().getId())
                .orElseThrow(() -> new IllegalArgumentException("Equipe não encontrada"));
        exigirNaoLider(usuario, equipe);
        usuario.setAtivo(false);
        usuarios.save(usuario);
    }

    private Usuario bloquear(UUID id) {
        return usuarios.findByIdForUpdate(obrigatorio(id, "id"))
                .orElseThrow(() -> new IllegalArgumentException("Usuário não encontrado"));
    }

    private void validarDados(String nome, String email, PerfilUsuario perfil) {
        obrigatorio(nome, "nome");
        obrigatorio(email, "email");
        obrigatorio(perfil, "perfil");
        // Email and AD uniqueness remain enforced by V1, including concurrent writes.
    }
}
