package com.empresa.pdi.service;

import com.empresa.pdi.enums.PerfilUsuario;
import com.empresa.pdi.enums.StatusAprovacaoPdi;
import com.empresa.pdi.enums.StatusExecucaoPdi;
import com.empresa.pdi.exception.PdiRascunhoNaoEditavelException;
import com.empresa.pdi.exception.PdiRascunhoNaoEncontradoException;
import com.empresa.pdi.model.PdiVersao;
import com.empresa.pdi.model.Usuario;
import com.empresa.pdi.repository.PDIRepository;
import com.empresa.pdi.repository.PdiVersaoRepository;
import com.empresa.pdi.repository.UsuarioRepository;
import com.empresa.pdi.security.UsuarioAutenticadoProvider;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** Shared authorization and lock discipline; write callers supply the transaction. */
@Service
public class PdiAcessoService {
    private final UsuarioRepository usuarios;
    private final PDIRepository pdis;
    private final PdiVersaoRepository versoes;
    private final EntityManager entityManager;
    private final ObjectProvider<UsuarioAutenticadoProvider> autenticacao;
    private final Set<UUID> rhAutorizados;

    public PdiAcessoService(UsuarioRepository usuarios, PDIRepository pdis,
                           PdiVersaoRepository versoes, EntityManager entityManager,
                           ObjectProvider<UsuarioAutenticadoProvider> autenticacao,
                           @Value("${talentsynk.pdi.rh-autorizados:}") String rhIds) {
        this.usuarios = usuarios;
        this.pdis = pdis;
        this.versoes = versoes;
        this.entityManager = entityManager;
        this.autenticacao = autenticacao;
        this.rhAutorizados = Arrays.stream(rhIds.split(",")).map(String::trim)
                .filter(s -> !s.isEmpty()).map(UUID::fromString).collect(Collectors.toUnmodifiableSet());
    }

    public Usuario usuarioAtual() {
        var provider = autenticacao.getIfAvailable();
        if (provider == null) throw new AuthenticationCredentialsNotFoundException("Autenticação necessária");
        UUID id = provider.obterIdUsuarioAutenticado();
        if (id == null) throw new AuthenticationCredentialsNotFoundException("Autenticação necessária");
        Usuario usuario = usuarios.findById(id).orElseThrow(PdiAcessoService::credenciaisInvalidas);
        // The identity provider may already have loaded this entity in the same persistence context.
        entityManager.refresh(usuario);
        if (!usuario.isAtivo()) throw credenciaisInvalidas();
        return usuario;
    }

    public Usuario bloquearTitular(UUID id) {
        Usuario usuario = bloquearUsuarios(Set.of(id)).get(id);
        if (!usuario.isAtivo()) throw credenciaisInvalidas();
        return usuario;
    }

    public Usuario bloquearSubmissao(UUID titularId) {
        UUID gestorId = usuarios.buscarGestorId(titularId).orElse(null);
        var ids = new TreeSet<UUID>();
        ids.add(titularId);
        if (gestorId != null) ids.add(gestorId);
        var bloqueados = bloquearUsuarios(ids);
        Usuario titular = bloqueados.get(titularId);
        if (!titular.isAtivo()) throw credenciaisInvalidas();
        UUID atual = titular.getGestor() == null ? null : titular.getGestor().getId();
        if (!Objects.equals(gestorId, atual)) throw conflito("O gestor foi alterado; consulte novamente o PDI");
        if (gestorId == null || gestorId.equals(titularId) || !bloqueados.get(gestorId).isAtivo()) {
            throw conflito("O titular precisa possuir um gestor ativo para submeter o PDI");
        }
        return titular;
    }

    public Usuario bloquearDecisao(Long pdiId) {
        Usuario ator = usuarioAtual();
        exigirPerfilDecisor(ator);
        UUID titularId = pdis.buscarTitularId(pdiId).orElseThrow(PdiRascunhoNaoEncontradoException::new);
        var ids = new TreeSet<UUID>();
        ids.add(ator.getId());
        ids.add(titularId);
        var bloqueados = bloquearUsuarios(ids);
        ator = bloqueados.get(ator.getId());
        validarDecisor(ator, bloqueados.get(titularId));
        return ator;
    }

    private Map<UUID, Usuario> bloquearUsuarios(Set<UUID> ids) {
        Map<UUID, Usuario> resultado = new HashMap<>();
        for (UUID id : new TreeSet<>(ids)) {
            Usuario usuario = usuarios.findByIdForUpdate(id).orElseThrow(PdiAcessoService::credenciaisInvalidas);
            // A locking query alone does not refresh an entity already present in the first-level cache.
            entityManager.refresh(usuario, LockModeType.PESSIMISTIC_WRITE);
            resultado.put(id, usuario);
        }
        return resultado;
    }

    public boolean rhAutorizado(Usuario ator) {
        return ator.isAtivo() && ator.getPerfilAcesso() == PerfilUsuario.RH_ADMIN
                && rhAutorizados.contains(ator.getId());
    }

    public void exigirPerfilDecisor(Usuario ator) {
        if (!ator.isAtivo()) throw credenciaisInvalidas();
        if (ator.getPerfilAcesso() != PerfilUsuario.GESTOR && !rhAutorizado(ator)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Usuário sem autorização para decidir PDI");
        }
    }

    public void validarDecisor(Usuario ator, Usuario titular) {
        exigirPerfilDecisor(ator);
        if (ator.getId().equals(titular.getId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "O titular não pode decidir o próprio PDI");
        }
        if (!titular.isAtivo()) throw conflito("O titular está inativo");
        if (!rhAutorizado(ator) && (titular.getGestor() == null
                || !ator.getId().equals(titular.getGestor().getId()))) {
            throw new PdiRascunhoNaoEncontradoException();
        }
    }

    public boolean podeConsultar(PdiVersao versao, Usuario ator) {
        if (!ator.isAtivo()) return false;
        Usuario titular = versao.getPdi().getUsuario();
        if (ator.getId().equals(titular.getId())) return true;
        if (versao.getSubmetidaEm() == null) return false;
        return rhAutorizado(ator) || (ator.getPerfilAcesso() == PerfilUsuario.GESTOR
                && titular.getGestor() != null && ator.getId().equals(titular.getGestor().getId()));
    }

    public PdiVersao versaoVisivel(Long pdiId, Long versaoId) {
        Usuario ator = usuarioAtual();
        PdiVersao versao = versoes.buscarPorId(pdiId, versaoId)
                .orElseThrow(PdiRascunhoNaoEncontradoException::new);
        if (!podeConsultar(versao, ator)) throw new PdiRascunhoNaoEncontradoException();
        return versao;
    }

    public static void validarRascunho(PdiVersao versao, Long esperada) {
        var pdi = versao.getPdi();
        if (versao.getStatusAprovacao() != StatusAprovacaoPdi.RASCUNHO
                || versao.getSubmetidaEm() != null || versao.getDecididaEm() != null
                || versao.getDecididoPor() != null || versao.getRetiradaEm() != null
                || pdi.getVersaoVigenteId() != null
                || pdi.getStatusExecucao() != StatusExecucaoPdi.NAO_INICIADO
                || (esperada == null && !Integer.valueOf(1).equals(versao.getNumero()))
                || (esperada != null && !esperada.equals(versao.getId()))) {
            throw new PdiRascunhoNaoEditavelException();
        }
    }

    private static BadCredentialsException credenciaisInvalidas() {
        return new BadCredentialsException("Autenticação inválida");
    }

    private static ResponseStatusException conflito(String mensagem) {
        return new ResponseStatusException(HttpStatus.CONFLICT, mensagem);
    }
}
