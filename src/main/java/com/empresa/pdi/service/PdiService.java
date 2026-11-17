package com.empresa.pdi.service;

import com.empresa.pdi.dto.CriarPdiRequest;
import com.empresa.pdi.dto.AtualizarPdiRascunhoRequest;
import com.empresa.pdi.exception.PdiRascunhoNaoEditavelException;
import com.empresa.pdi.dto.PdiRascunhoResponse;
import com.empresa.pdi.dto.PdiSubmetidoResponse;
import com.empresa.pdi.dto.PdiDecisaoRequest;
import com.empresa.pdi.dto.PdiDecisaoResponse;
import com.empresa.pdi.dto.PdiVersaoResponse;
import com.empresa.pdi.enums.StatusAprovacaoPdi;
import com.empresa.pdi.enums.StatusExecucaoPdi;
import com.empresa.pdi.model.PDI;
import com.empresa.pdi.model.PdiEvento;
import com.empresa.pdi.model.PdiVersao;
import com.empresa.pdi.model.Usuario;
import com.empresa.pdi.model.AcaoPDI;
import com.empresa.pdi.model.AcaoPdiVersao;
import com.empresa.pdi.exception.PdiRascunhoNaoEncontradoException;
import java.util.List;
import com.empresa.pdi.repository.PDIRepository;
import com.empresa.pdi.repository.PdiEventoRepository;
import com.empresa.pdi.repository.PdiVersaoRepository;
import com.empresa.pdi.repository.AcaoPdiVersaoRepository;
import com.empresa.pdi.repository.UsuarioRepository;
import com.empresa.pdi.repository.AcaoPDIRepository;
import com.empresa.pdi.security.UsuarioAutenticadoProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class PdiService {
    private final PDIRepository pdis;
    private final PdiVersaoRepository versoes;
    private final AcaoPdiVersaoRepository acoesVersoes;
    private final PdiEventoRepository eventos;
    private final UsuarioRepository usuarios;
    private final ObjectProvider<UsuarioAutenticadoProvider> autenticacao;
    private final ObjectMapper objectMapper;
    private final AcaoPDIRepository acoes;
    private final PdiAcessoService acesso;

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
        var titular = acesso.bloquearTitular(titularId);

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
        return atualizarRascunho(id, request, null);
    }

    @Transactional
    public PdiRascunhoResponse atualizarRascunho(Long id, AtualizarPdiRascunhoRequest request, Long versaoId) {
        UUID titularId = obterTitularId();
        var titular = acesso.bloquearTitular(titularId);
        if (id == null || id <= 0) throw new PdiRascunhoNaoEncontradoException();
        PDI pdi = pdis.buscarDoTitularParaAtualizacao(id, titularId)
                .orElseThrow(PdiRascunhoNaoEncontradoException::new);
        PdiVersao versao = versoes.buscarEditavelParaAtualizacao(pdi.getId())
                .orElseThrow(PdiRascunhoNaoEditavelException::new);
        PdiAcessoService.validarRascunho(versao, versaoId);
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

    /** Submit the exact editable proposal, including a correction after rejection. */
    @Transactional
    public PdiSubmetidoResponse submeter(Long id) {
        return submeter(id, null);
    }

    @Transactional
    public PdiSubmetidoResponse submeter(Long id, Long versaoId) {
        UUID titularId = obterTitularId();
        if (id == null || id <= 0) throw new PdiRascunhoNaoEncontradoException();
        pdis.buscarTitularId(id).filter(titularId::equals)
                .orElseThrow(PdiRascunhoNaoEncontradoException::new);
        // All involved user locks precede aggregate locks, in UUID order.
        Usuario titular = acesso.bloquearSubmissao(titularId);
        PDI pdi = pdis.buscarDoTitularParaAtualizacao(id, titularId)
                .orElseThrow(PdiRascunhoNaoEncontradoException::new);
        PdiVersao versao = versoes.buscarEditavelParaAtualizacao(pdi.getId())
                .orElseThrow(PdiRascunhoNaoEditavelException::new);
        PdiAcessoService.validarRascunho(versao, versaoId);
        if (acoesVersoes.contarNaVersao(pdi.getId(), versao.getId()) < 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Cadastre pelo menos uma ação antes de submeter o PDI");
        }
        Usuario gestorAtivo = titular.getGestor();

        Instant agora = Instant.now();
        var anterior = objectMapper.createObjectNode()
                .put("statusAprovacao", StatusAprovacaoPdi.RASCUNHO.name())
                .putNull("submetidaEm")
                .putNull("gestorNaSubmissaoId");
        versao.setStatusAprovacao(StatusAprovacaoPdi.AGUARDANDO_APROVACAO);
        versao.setSubmetidaEm(agora);
        versao.setGestorNaSubmissao(gestorAtivo);
        versoes.saveAndFlush(versao);
        var dados = objectMapper.createObjectNode()
                .put("statusAprovacao", versao.getStatusAprovacao().name())
                .put("submetidaEm", agora.toString())
                .put("gestorNaSubmissaoId", gestorAtivo.getId().toString());
        eventos.saveAndFlush(new PdiEvento(versao, null, null, null, null, titular,
                "PDI_SUBMETIDO", null, dados, anterior));
        return paraSubmetidoResponse(versao);
    }

    /** Submitted proposals remain visible even after they leave RASCUNHO. */
    @Transactional(readOnly = true)
    public List<PdiSubmetidoResponse> listarSubmetidos() {
        return versoes.listarSubmetidos(obterTitularAtivoId()).stream()
                .map(PdiService::paraSubmetidoResponse).toList();
    }

    @Transactional(readOnly = true)
    public List<PdiVersaoResponse> listarPendentes() {
        Usuario ator = acesso.usuarioAtual();
        acesso.exigirPerfilDecisor(ator);
        return versoes.listarPendentes(ator.getId(), acesso.rhAutorizado(ator)).stream()
                .filter(v -> acesso.podeConsultar(v, ator)).map(PdiService::paraVersaoResponse).toList();
    }

    @Transactional(readOnly = true)
    public List<PdiVersaoResponse> listarVersoes(Long id) {
        Usuario ator = acesso.usuarioAtual();
        List<PdiVersaoResponse> visiveis = versoes.listarVersoes(id).stream()
                .filter(v -> acesso.podeConsultar(v, ator)).map(PdiService::paraVersaoResponse).toList();
        if (visiveis.isEmpty()) throw new PdiRascunhoNaoEncontradoException();
        return visiveis;
    }

    @Transactional(readOnly = true)
    public PdiVersaoResponse buscarVersao(Long id, Long versaoId) {
        return paraVersaoResponse(acesso.versaoVisivel(id, versaoId));
    }

    @Transactional
    public PdiDecisaoResponse aprovar(Long id, Long versaoId, PdiDecisaoRequest request) {
        PdiVersao versao = prepararDecisao(id, versaoId);
        Usuario ator = acesso.usuarioAtual();
        String justificativa = normalizarJustificativa(request);
        var anterior = objectMapper.valueToTree(paraVersaoResponse(versao));
        decidir(versao, ator, StatusAprovacaoPdi.APROVADO, justificativa);
        versoes.saveAndFlush(versao);
        versao.getPdi().setVersaoVigente(versao);
        pdis.saveAndFlush(versao.getPdi());
        var response = paraVersaoResponse(versao);
        eventos.saveAndFlush(new PdiEvento(versao, null, null, null, null, ator,
                "PDI_APROVADO", justificativa, objectMapper.valueToTree(response), anterior));
        return new PdiDecisaoResponse(response, null, null);
    }

    @Transactional
    public PdiDecisaoResponse reprovar(Long id, Long versaoId, PdiDecisaoRequest request) {
        String justificativa = normalizarJustificativa(request);
        if (justificativa == null) throw new IllegalArgumentException("A rejeição exige justificativa");
        PdiVersao versao = prepararDecisao(id, versaoId);
        Usuario ator = acesso.usuarioAtual();
        var anterior = objectMapper.valueToTree(paraVersaoResponse(versao));
        decidir(versao, ator, StatusAprovacaoPdi.REPROVADO, justificativa);
        // Release the partial unique index entry before inserting the next open proposal.
        versoes.saveAndFlush(versao);

        PdiVersao nova = new PdiVersao();
        nova.setPdi(versao.getPdi());
        nova.setNumero(versoes.proximoNumero(id));
        nova.setTitulo(versao.getTitulo());
        nova.setObjetivo(versao.getObjetivo());
        nova.setInicioPlanejado(versao.getInicioPlanejado());
        nova.setFimPlanejado(versao.getFimPlanejado());
        nova = versoes.saveAndFlush(nova);
        var dadosCopia = objectMapper.createObjectNode()
                .put("versaoOrigemId", versao.getId()).put("versaoDestinoId", nova.getId());
        var mapaAcoes = dadosCopia.putArray("acoesCopiadas");
        for (AcaoPdiVersao origem : acoesVersoes.listarNaVersao(id, versaoId)) {
            AcaoPDI identidade = new AcaoPDI();
            identidade.setPdi(versao.getPdi());
            identidade = acoes.saveAndFlush(identidade);
            AcaoPdiVersao copia = new AcaoPdiVersao();
            copia.definirVinculos(nova, identidade);
            copia.setTitulo(origem.getTitulo());
            copia.setObjetivo(origem.getObjetivo());
            copia.setResultadoEsperado(origem.getResultadoEsperado());
            copia.setInicioPlanejado(origem.getInicioPlanejado());
            copia.setFimPlanejado(origem.getFimPlanejado());
            copia.setOrdem(origem.getOrdem());
            copia = acoesVersoes.saveAndFlush(copia);
            mapaAcoes.addObject().put("acaoOrigemId", origem.getAcaoPdiId())
                    .put("acaoVersaoOrigemId", origem.getId()).put("acaoDestinoId", identidade.getId())
                    .put("acaoVersaoDestinoId", copia.getId());
        }
        var response = paraVersaoResponse(versao);
        var dadosDecisao = objectMapper.valueToTree(response);
        eventos.saveAndFlush(new PdiEvento(versao, null, null, null, null, ator,
                "PDI_REPROVADO", justificativa, dadosDecisao, anterior));
        eventos.saveAndFlush(new PdiEvento(nova, null, null, null, null, ator,
                "REVISAO_CRIADA", null, dadosCopia, null));
        return new PdiDecisaoResponse(response, nova.getId(), nova.getNumero());
    }

    private PdiVersao prepararDecisao(Long id, Long versaoId) {
        if (id == null || id <= 0 || versaoId == null || versaoId <= 0) {
            throw new PdiRascunhoNaoEncontradoException();
        }
        Usuario ator = acesso.bloquearDecisao(id);
        PDI pdi = pdis.buscarParaAtualizacao(id).orElseThrow(PdiRascunhoNaoEncontradoException::new);
        acesso.validarDecisor(ator, pdi.getUsuario());
        PdiVersao versao = versoes.buscarPorIdParaAtualizacao(id, versaoId)
                .orElseThrow(PdiRascunhoNaoEncontradoException::new);
        if (versao.getStatusAprovacao() != StatusAprovacaoPdi.AGUARDANDO_APROVACAO
                || versao.getSubmetidaEm() == null || versao.getDecididaEm() != null
                || versao.getDecididoPor() != null || versao.getRetiradaEm() != null
                || pdi.getStatusExecucao() != StatusExecucaoPdi.NAO_INICIADO
                || pdi.getVersaoVigenteId() != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "A versão não está disponível para decisão");
        }
        return versao;
    }

    private static void decidir(PdiVersao versao, Usuario ator, StatusAprovacaoPdi status, String justificativa) {
        versao.setStatusAprovacao(status);
        versao.setDecididoPor(ator);
        versao.setDecididaEm(Instant.now());
        versao.setJustificativaDecisao(justificativa);
    }

    private static String normalizarJustificativa(PdiDecisaoRequest request) {
        if (request == null || request.justificativa() == null
                || request.justificativa().codePoints().allMatch(c -> Character.isWhitespace(c) || Character.isSpaceChar(c))) {
            return null;
        }
        return request.justificativa().strip();
    }

    private static PdiVersaoResponse paraVersaoResponse(PdiVersao versao) {
        PDI pdi = versao.getPdi();
        Usuario titular = pdi.getUsuario();
        return new PdiVersaoResponse(pdi.getId(), versao.getId(), versao.getNumero(), titular.getId(),
                titular.getGestor() == null ? null : titular.getGestor().getId(), pdi.getVersaoVigenteId(),
                pdi.getStatusExecucao(), versao.getStatusAprovacao(), versao.getTitulo(), versao.getObjetivo(),
                versao.getInicioPlanejado(), versao.getFimPlanejado(), pdi.getCriadoEm(), versao.getCriadaEm(),
                versao.getSubmetidaEm(), versao.getGestorNaSubmissao() == null ? null : versao.getGestorNaSubmissao().getId(),
                versao.getDecididaEm(), versao.getDecididoPor() == null ? null : versao.getDecididoPor().getId(),
                versao.getJustificativaDecisao());
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

    private static PdiSubmetidoResponse paraSubmetidoResponse(PdiVersao versao) {
        PDI pdi = versao.getPdi();
        return new PdiSubmetidoResponse(pdi.getId(), versao.getId(), versao.getNumero(),
                pdi.getVersaoVigenteId(), pdi.getStatusExecucao(), versao.getStatusAprovacao(),
                versao.getTitulo(), versao.getObjetivo(), versao.getInicioPlanejado(),
                versao.getFimPlanejado(), pdi.getCriadoEm(), versao.getCriadaEm(),
                versao.getSubmetidaEm(), versao.getGestorNaSubmissao() == null
                        ? null : versao.getGestorNaSubmissao().getId());
    }

    private static PdiRascunhoResponse paraResponse(PdiVersao versao) {
        PDI pdi = versao.getPdi();
        return new PdiRascunhoResponse(pdi.getId(), versao.getId(), versao.getNumero(),
                pdi.getVersaoVigenteId(), pdi.getStatusExecucao(), versao.getStatusAprovacao(),
                versao.getTitulo(), versao.getObjetivo(), versao.getInicioPlanejado(),
                versao.getFimPlanejado(), pdi.getCriadoEm(), versao.getCriadaEm());
    }
}
