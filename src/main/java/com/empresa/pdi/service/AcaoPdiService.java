package com.empresa.pdi.service;

import com.empresa.pdi.dto.AcaoPdiResponse;
import com.empresa.pdi.dto.AtualizarAcaoPdiRequest;
import com.empresa.pdi.dto.CriarAcaoPdiRequest;
import com.empresa.pdi.enums.StatusAprovacaoPdi;
import com.empresa.pdi.enums.StatusExecucaoAcao;
import com.empresa.pdi.enums.StatusExecucaoPdi;
import com.empresa.pdi.exception.PdiRascunhoNaoEditavelException;
import com.empresa.pdi.exception.PdiRascunhoNaoEncontradoException;
import com.empresa.pdi.model.AcaoPDI;
import com.empresa.pdi.model.AcaoPdiVersao;
import com.empresa.pdi.model.PdiEvento;
import com.empresa.pdi.model.PdiVersao;
import com.empresa.pdi.model.Usuario;
import com.empresa.pdi.repository.AcaoPDIRepository;
import com.empresa.pdi.repository.AcaoPdiVersaoRepository;
import com.empresa.pdi.repository.PDIRepository;
import com.empresa.pdi.repository.PdiEventoRepository;
import com.empresa.pdi.repository.PdiVersaoRepository;
import com.empresa.pdi.repository.UsuarioRepository;
import com.empresa.pdi.security.UsuarioAutenticadoProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AcaoPdiService {
    private final AcaoPDIRepository acoes;
    private final AcaoPdiVersaoRepository acoesVersoes;
    private final PDIRepository pdis;
    private final PdiVersaoRepository versoes;
    private final PdiEventoRepository eventos;
    private final UsuarioRepository usuarios;
    private final ObjectProvider<UsuarioAutenticadoProvider> autenticacao;
    private final ObjectMapper objectMapper;
    private final PdiAcessoService acesso;

    @Transactional
    public AcaoPdiResponse criar(Long pdiId, CriarAcaoPdiRequest request) {
        return criar(pdiId, request, null);
    }

    @Transactional
    public AcaoPdiResponse criar(Long pdiId, CriarAcaoPdiRequest request, Long versaoId) {
        UUID titularId = obterTitularId();
        // Shared lifecycle lock order: users -> PDI -> editable version.
        Usuario titular = acesso.bloquearTitular(titularId);
        validarId(pdiId);
        var pdi = pdis.buscarDoTitularParaAtualizacao(pdiId, titularId)
                .orElseThrow(PdiRascunhoNaoEncontradoException::new);
        PdiVersao versao = versoes.buscarEditavelParaAtualizacao(pdi.getId())
                .orElseThrow(PdiRascunhoNaoEditavelException::new);
        PdiAcessoService.validarRascunho(versao, versaoId);
        if (request == null || (request.inicioPlanejado() != null && request.fimPlanejado() != null
                && request.inicioPlanejado().isAfter(request.fimPlanejado()))) {
            throw new IllegalArgumentException("Dados da ação inválidos");
        }

        AcaoPDI acao = new AcaoPDI();
        acao.setPdi(pdi);
        acao.setStatusExecucao(StatusExecucaoAcao.NAO_INICIADA);
        acao = acoes.saveAndFlush(acao);
        AcaoPdiVersao conteudo = new AcaoPdiVersao();
        conteudo.definirVinculos(versao, acao);
        conteudo.setTitulo(request.titulo());
        conteudo.setObjetivo(request.objetivo());
        conteudo.setResultadoEsperado(request.resultadoEsperado());
        conteudo.setInicioPlanejado(request.inicioPlanejado());
        conteudo.setFimPlanejado(request.fimPlanejado());
        conteudo.setOrdem(0);
        conteudo = acoesVersoes.saveAndFlush(conteudo);
        AcaoPdiResponse response = paraResponse(conteudo);
        // Both persisted references are required by the event's composite FKs.
        eventos.saveAndFlush(new PdiEvento(versao, acao, conteudo, null, null, titular,
                "ACAO_ADICIONADA", null, objectMapper.valueToTree(response), null));
        return response;
    }

    @Transactional
    public AcaoPdiResponse atualizarRascunho(Long pdiId, Long acaoId, AtualizarAcaoPdiRequest request) {
        return atualizarRascunho(pdiId, acaoId, request, null);
    }

    @Transactional
    public AcaoPdiResponse atualizarRascunho(Long pdiId, Long acaoId,
                                           AtualizarAcaoPdiRequest request, Long versaoId) {
        UUID titularId = obterTitularId();
        Usuario titular = acesso.bloquearTitular(titularId);
        validarId(pdiId);
        validarId(acaoId);
        var pdi = pdis.buscarDoTitularParaAtualizacao(pdiId, titularId)
                .orElseThrow(PdiRascunhoNaoEncontradoException::new);
        PdiVersao versao = versoes.buscarEditavelParaAtualizacao(pdi.getId())
                .orElseThrow(PdiRascunhoNaoEditavelException::new);
        PdiAcessoService.validarRascunho(versao, versaoId);
        if (request == null || (request.inicioPlanejado() != null && request.fimPlanejado() != null
                && request.inicioPlanejado().isAfter(request.fimPlanejado()))) {
            throw new IllegalArgumentException("Dados da ação inválidos");
        }
        AcaoPdiVersao conteudo = acoesVersoes.buscarNaVersao(pdi.getId(), versao.getId(), acaoId)
                .orElseThrow(PdiRascunhoNaoEncontradoException::new);

        // An unchanged PUT must not dirty the content or append a historical event.
        if (Objects.equals(conteudo.getTitulo(), request.titulo())
                && Objects.equals(conteudo.getObjetivo(), request.objetivo())
                && Objects.equals(conteudo.getInicioPlanejado(), request.inicioPlanejado())
                && Objects.equals(conteudo.getFimPlanejado(), request.fimPlanejado())
                && Objects.equals(conteudo.getResultadoEsperado(), request.resultadoEsperado())) {
            return paraResponse(conteudo);
        }

        ObjectNode anterior = conteudoParaHistorico(conteudo);
        conteudo.setTitulo(request.titulo());
        conteudo.setObjetivo(request.objetivo());
        conteudo.setInicioPlanejado(request.inicioPlanejado());
        conteudo.setFimPlanejado(request.fimPlanejado());
        conteudo.setResultadoEsperado(request.resultadoEsperado());
        conteudo = acoesVersoes.saveAndFlush(conteudo);
        AcaoPdiResponse response = paraResponse(conteudo);
        eventos.saveAndFlush(new PdiEvento(versao, conteudo.getAcaoPdi(), conteudo, null, null, titular,
                "ACAO_EDITADA", null, conteudoParaHistorico(conteudo), anterior));
        return response;
    }

    @Transactional
    public void remover(Long pdiId, Long acaoId, Long versaoId) {
        UUID titularId = obterTitularId();
        Usuario titular = acesso.bloquearTitular(titularId);
        validarId(pdiId);
        validarId(acaoId);
        var pdi = pdis.buscarDoTitularParaAtualizacao(pdiId, titularId)
                .orElseThrow(PdiRascunhoNaoEncontradoException::new);
        PdiVersao versao = versoes.buscarEditavelParaAtualizacao(pdiId)
                .orElseThrow(PdiRascunhoNaoEditavelException::new);
        PdiAcessoService.validarRascunho(versao, versaoId);
        AcaoPdiVersao conteudo = acoesVersoes.buscarNaVersao(pdi.getId(), versao.getId(), acaoId)
                .orElseThrow(PdiRascunhoNaoEncontradoException::new);
        ObjectNode anterior = conteudoParaHistorico(conteudo);
        conteudo.setRemovidaEm(Instant.now());
        acoesVersoes.saveAndFlush(conteudo);
        eventos.saveAndFlush(new PdiEvento(versao, conteudo.getAcaoPdi(), conteudo, null, null, titular,
                "ACAO_REMOVIDA", null, objectMapper.createObjectNode()
                        .put("acaoId", acaoId).put("removidaEm", conteudo.getRemovidaEm().toString()), anterior));
    }

    @Transactional(readOnly = true)
    public List<AcaoPdiResponse> listarNaVersao(Long pdiId, Long versaoId) {
        PdiVersao versao = acesso.versaoVisivel(pdiId, versaoId);
        return acoesVersoes.listarNaVersao(pdiId, versao.getId()).stream()
                .map(AcaoPdiService::paraResponse).toList();
    }

    @Transactional(readOnly = true)
    public List<AcaoPdiResponse> listar(Long pdiId) {
        PdiVersao versao = buscarRascunhoPrivado(pdiId);
        return acoesVersoes.listarNaVersao(pdiId, versao.getId()).stream()
                .map(AcaoPdiService::paraResponse).toList();
    }

    @Transactional(readOnly = true)
    public AcaoPdiResponse buscar(Long pdiId, Long acaoId) {
        PdiVersao versao = buscarRascunhoPrivado(pdiId);
        validarId(acaoId);
        return acoesVersoes.buscarNaVersao(pdiId, versao.getId(), acaoId)
                .map(AcaoPdiService::paraResponse)
                .orElseThrow(PdiRascunhoNaoEncontradoException::new);
    }

    private PdiVersao buscarRascunhoPrivado(Long pdiId) {
        UUID titularId = obterTitularId();
        usuarios.findById(titularId).filter(Usuario::isAtivo)
                .orElseThrow(() -> new BadCredentialsException("Autenticação inválida"));
        validarId(pdiId);
        PdiVersao versao = versoes.buscarRascunho(pdiId, titularId, StatusAprovacaoPdi.RASCUNHO)
                .orElseThrow(PdiRascunhoNaoEncontradoException::new);
        // Reads do not require an expected version; writes always enforce it for revisions.
        if (versao.getStatusAprovacao() != StatusAprovacaoPdi.RASCUNHO
                || versao.getSubmetidaEm() != null
                || versao.getPdi().getVersaoVigenteId() != null
                || versao.getPdi().getStatusExecucao() != StatusExecucaoPdi.NAO_INICIADO) {
            throw new PdiRascunhoNaoEncontradoException();
        }
        return versao;
    }

    private UUID obterTitularId() {
        UsuarioAutenticadoProvider provider = autenticacao.getIfAvailable();
        if (provider == null) throw new AuthenticationCredentialsNotFoundException("Autenticação necessária");
        UUID id = provider.obterIdUsuarioAutenticado();
        if (id == null) throw new AuthenticationCredentialsNotFoundException("Autenticação necessária");
        return id;
    }

    private static void validarId(Long id) {
        if (id == null || id <= 0) throw new PdiRascunhoNaoEncontradoException();
    }

    private ObjectNode conteudoParaHistorico(AcaoPdiVersao conteudo) {
        // Keep exactly the editable fields, explicit nulls and ISO calendar dates.
        return objectMapper.createObjectNode()
                .put("titulo", conteudo.getTitulo())
                .put("objetivo", conteudo.getObjetivo())
                .put("inicioPlanejado", conteudo.getInicioPlanejado() == null
                        ? null : conteudo.getInicioPlanejado().toString())
                .put("fimPlanejado", conteudo.getFimPlanejado() == null
                        ? null : conteudo.getFimPlanejado().toString())
                .put("resultadoEsperado", conteudo.getResultadoEsperado());
    }

    private static AcaoPdiResponse paraResponse(AcaoPdiVersao versao) {
        return new AcaoPdiResponse(versao.getAcaoPdiId(), versao.getId(), versao.getPdiId(),
                versao.getPdiVersaoId(), versao.getTitulo(), versao.getObjetivo(),
                versao.getResultadoEsperado(), versao.getInicioPlanejado(), versao.getFimPlanejado(),
                versao.getOrdem(), versao.getAcaoPdi().getStatusExecucao());
    }
}
