package com.empresa.pdi.service;

import com.empresa.pdi.dto.CriarPdiRequest;
import com.empresa.pdi.dto.AtualizarPdiRascunhoRequest;
import com.empresa.pdi.exception.PdiRascunhoNaoEditavelException;
import com.empresa.pdi.dto.PdiRascunhoResponse;
import com.empresa.pdi.enums.StatusAprovacaoPdi;
import com.empresa.pdi.enums.StatusExecucaoPdi;
import com.empresa.pdi.model.PDI;
import com.empresa.pdi.model.PdiEvento;
import com.empresa.pdi.model.PdiVersao;
import com.empresa.pdi.exception.PdiRascunhoNaoEncontradoException;
import java.util.List;
import com.empresa.pdi.repository.PDIRepository;
import com.empresa.pdi.repository.PdiEventoRepository;
import com.empresa.pdi.repository.PdiVersaoRepository;
import com.empresa.pdi.repository.UsuarioRepository;
import com.empresa.pdi.security.UsuarioAutenticadoProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class PdiService {
    private final PDIRepository pdis;
    private final PdiVersaoRepository versoes;
    private final PdiEventoRepository eventos;
    private final UsuarioRepository usuarios;
    private final ObjectProvider<UsuarioAutenticadoProvider> autenticacao;
    private final ObjectMapper objectMapper;

    @Transactional
    public PdiRascunhoResponse criar(CriarPdiRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Os dados do rascunho são necessários");
        }
        // Draft text and dates may be incomplete. Do not impose submission requirements.
        if (request.inicioPlanejado() != null && request.fimPlanejado() != null
                && request.inicioPlanejado().isAfter(request.fimPlanejado())) {
            throw new IllegalArgumentException("O início planejado não pode ser posterior ao fim planejado");
        }

        UUID titularId = obterTitularId();
        // Coordinate the active-user check with existing CORE operations using the same lock.
        var titular = usuarios.findByIdForUpdate(titularId)
                .filter(usuario -> usuario.isAtivo())
                .orElseThrow(() -> new BadCredentialsException("Autenticação inválida"));

        PDI pdi = new PDI();
        pdi.setUsuario(titular);
        pdi.setStatusExecucao(StatusExecucaoPdi.NAO_INICIADO);
        pdi = pdis.saveAndFlush(pdi);

        PdiVersao versao = new PdiVersao();
        versao.setPdi(pdi);
        versao.setNumero(1);
        versao.setStatusAprovacao(StatusAprovacaoPdi.RASCUNHO);
        versao.setTitulo(request.titulo());
        versao.setObjetivo(request.objetivo());
        versao.setInicioPlanejado(request.inicioPlanejado());
        versao.setFimPlanejado(request.fimPlanejado());
        versao = versoes.saveAndFlush(versao);

        // Never promote a draft to approved-current version.
        PdiRascunhoResponse response = paraResponse(versao);

        // Content remains private with its never-submitted version. No JPA graph is serialized.
        PdiEvento evento = new PdiEvento(versao, null, null, null, null, titular,
                "CRIACAO", null, objectMapper.valueToTree(response), null);
        eventos.saveAndFlush(evento);
        return response;
    }

    @Transactional
    public PdiRascunhoResponse atualizarRascunho(Long id, AtualizarPdiRascunhoRequest request) {
        UUID titularId = obterTitularId();
        // Lock order: active owner, PDI identity, initial version. Future lifecycle
        // writes must coordinate on the same PDI lock before changing its versions.
        var titular = usuarios.findByIdForUpdate(titularId)
                .filter(usuario -> usuario.isAtivo())
                .orElseThrow(() -> new BadCredentialsException("Autenticação inválida"));
        if (id == null || id <= 0) throw new PdiRascunhoNaoEncontradoException();
        PDI pdi = pdis.buscarDoTitularParaAtualizacao(id, titularId)
                .orElseThrow(PdiRascunhoNaoEncontradoException::new);
        PdiVersao versao = versoes.buscarInicialParaAtualizacao(pdi.getId())
                .orElseThrow(PdiRascunhoNaoEditavelException::new);
        if (versao.getStatusAprovacao() != StatusAprovacaoPdi.RASCUNHO
                || versao.getSubmetidaEm() != null
                || pdi.getVersaoVigenteId() != null
                || pdi.getStatusExecucao() != StatusExecucaoPdi.NAO_INICIADO
                || versoes.contarVersoesSubmetidas(pdi.getId()) != 0) {
            throw new PdiRascunhoNaoEditavelException();
        }
        if (request == null || (request.inicioPlanejado() != null && request.fimPlanejado() != null
                && request.inicioPlanejado().isAfter(request.fimPlanejado()))) {
            throw new IllegalArgumentException("Dados do rascunho inválidos");
        }

        // Capture the previous content before mutating the managed version.
        var anterior = objectMapper.valueToTree(paraResponse(versao));
        versao.setTitulo(request.titulo());
        versao.setObjetivo(request.objetivo());
        versao.setInicioPlanejado(request.inicioPlanejado());
        versao.setFimPlanejado(request.fimPlanejado());
        versoes.saveAndFlush(versao);
        PdiRascunhoResponse response = paraResponse(versao);
        // Private draft history: snapshot holds the previous content, dados the new content.
        eventos.saveAndFlush(new PdiEvento(versao, null, null, null, null, titular,
                "EDICAO_RASCUNHO", null, objectMapper.valueToTree(response), anterior));
        return response;
    }

    @Transactional(readOnly = true)
    public PdiRascunhoResponse buscarRascunho(Long id) {
        UUID titularId = obterTitularAtivoId();
        if (id == null || id <= 0) throw new PdiRascunhoNaoEncontradoException();
        return versoes.buscarRascunho(id, titularId, StatusAprovacaoPdi.RASCUNHO)
                .map(PdiService::paraResponse).orElseThrow(PdiRascunhoNaoEncontradoException::new);
    }

    @Transactional(readOnly = true)
    public List<PdiRascunhoResponse> listarRascunhos() {
        return versoes.listarRascunhos(obterTitularAtivoId(), StatusAprovacaoPdi.RASCUNHO)
                .stream().map(PdiService::paraResponse).toList();
    }

    private UUID obterTitularId() {
        UsuarioAutenticadoProvider provider = autenticacao.getIfAvailable();
        if (provider == null) throw new AuthenticationCredentialsNotFoundException("Autenticação necessária");
        UUID id = provider.obterIdUsuarioAutenticado();
        if (id == null) throw new AuthenticationCredentialsNotFoundException("Autenticação necessária");
        return id;
    }

    private UUID obterTitularAtivoId() {
        UUID id = obterTitularId();
        if (usuarios.findById(id).filter(usuario -> usuario.isAtivo()).isEmpty()) {
            throw new BadCredentialsException("Autenticação inválida");
        }
        return id;
    }

    private static PdiRascunhoResponse paraResponse(PdiVersao versao) {
        PDI pdi = versao.getPdi();
        return new PdiRascunhoResponse(pdi.getId(), versao.getId(), versao.getNumero(),
                pdi.getVersaoVigenteId(), pdi.getStatusExecucao(), versao.getStatusAprovacao(),
                versao.getTitulo(), versao.getObjetivo(), versao.getInicioPlanejado(),
                versao.getFimPlanejado(), pdi.getCriadoEm(), versao.getCriadaEm());
    }
}
