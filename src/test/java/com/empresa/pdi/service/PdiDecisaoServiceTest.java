package com.empresa.pdi.service;

import com.empresa.pdi.dto.*;
import com.empresa.pdi.enums.*;
import com.empresa.pdi.exception.PdiRascunhoNaoEncontradoException;
import com.empresa.pdi.model.*;
import com.empresa.pdi.repository.*;
import com.empresa.pdi.security.UsuarioAutenticadoProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Business decisions and independent copies; transaction rollback requires PostgreSQL IT. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PdiDecisaoServiceTest {
    private static final UUID OWNER = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID MANAGER = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID RH = UUID.fromString("33333333-3333-4333-8333-333333333333");
    @Mock PDIRepository pdis;
    @Mock PdiVersaoRepository versoes;
    @Mock AcaoPdiVersaoRepository conteudos;
    @Mock AcaoPDIRepository acoes;
    @Mock PdiEventoRepository eventos;
    @Mock UsuarioRepository usuarios;
    @Mock EntityManager em;
    @Mock ObjectProvider<UsuarioAutenticadoProvider> autenticacao;
    @Mock UsuarioAutenticadoProvider principal;
    private PdiService service;
    private Usuario owner;
    private Usuario manager;
    private Usuario rh;
    private PDI pdi;
    private PdiVersao submitted;
    private AcaoPdiVersao source;

    @BeforeEach
    void fixtures() {
        owner = user(OWNER, PerfilUsuario.COLABORADOR);
        manager = user(MANAGER, PerfilUsuario.GESTOR);
        rh = user(RH, PerfilUsuario.RH_ADMIN);
        owner.setGestor(manager);
        pdi = new PDI();
        ReflectionTestUtils.setField(pdi, "id", 11L);
        pdi.setUsuario(owner);
        submitted = new PdiVersao();
        ReflectionTestUtils.setField(submitted, "id", 21L);
        submitted.setPdi(pdi);
        submitted.setNumero(1);
        submitted.setTitulo("Plano submetido");
        submitted.setObjetivo("Objetivo preservado");
        submitted.setInicioPlanejado(LocalDate.of(2026, 1, 1));
        submitted.setFimPlanejado(LocalDate.of(2026, 12, 31));
        submitted.setStatusAprovacao(StatusAprovacaoPdi.AGUARDANDO_APROVACAO);
        submitted.setSubmetidaEm(Instant.parse("2026-01-01T00:00:00Z"));
        submitted.setGestorNaSubmissao(manager);
        AcaoPDI identity = new AcaoPDI();
        ReflectionTestUtils.setField(identity, "id", 31L);
        identity.setPdi(pdi);
        source = new AcaoPdiVersao();
        ReflectionTestUtils.setField(source, "id", 41L);
        source.definirVinculos(submitted, identity);
        source.setTitulo("Acao original");
        source.setObjetivo("Objetivo da acao");
        source.setResultadoEsperado("Entrega planejada");
        source.setInicioPlanejado(LocalDate.of(2026, 3, 1));
        source.setFimPlanejado(LocalDate.of(2026, 3, 31));
        source.setOrdem(7);
        when(autenticacao.getIfAvailable()).thenReturn(principal);
        when(principal.obterIdUsuarioAutenticado()).thenReturn(MANAGER);
        when(usuarios.findById(any(UUID.class))).thenAnswer(call -> userFor(call.getArgument(0)));
        when(usuarios.findByIdForUpdate(any(UUID.class))).thenAnswer(call -> userFor(call.getArgument(0)));
        when(pdis.buscarTitularId(11L)).thenReturn(Optional.of(OWNER));
        when(pdis.buscarParaAtualizacao(11L)).thenReturn(Optional.of(pdi));
        when(versoes.buscarPorIdParaAtualizacao(11L, 21L)).thenReturn(Optional.of(submitted));
        when(versoes.proximoNumero(11L)).thenReturn(2);
        when(versoes.saveAndFlush(any())).thenAnswer(call -> {
            PdiVersao value = call.getArgument(0);
            if (value.getId() == null) ReflectionTestUtils.setField(value, "id", 22L);
            return value;
        });
        when(conteudos.listarNaVersao(11L, 21L)).thenReturn(List.of(source));
        AtomicLong actionSequence = new AtomicLong(31L);
        when(acoes.saveAndFlush(any())).thenAnswer(call -> {
            AcaoPDI value = call.getArgument(0);
            ReflectionTestUtils.setField(value, "id", actionSequence.incrementAndGet());
            return value;
        });
        when(conteudos.saveAndFlush(any())).thenAnswer(call -> {
            AcaoPdiVersao value = call.getArgument(0);
            ReflectionTestUtils.setField(value, "id", 42L);
            return value;
        });
        service = service("");
    }

    @Test
    void approvalPromotesExactVersionWithoutStartingPdiOrActionsAndAuditsDecision() {
        var result = service.aprovar(11L, 21L, new PdiDecisaoRequest("  Conforme  "));
        assertEquals(StatusAprovacaoPdi.APROVADO, submitted.getStatusAprovacao());
        assertSame(submitted, pdi.getVersaoVigente());
        assertEquals(21L, pdi.getVersaoVigenteId());
        assertEquals(StatusExecucaoPdi.NAO_INICIADO, pdi.getStatusExecucao());
        assertNull(pdi.getIniciadoEm());
        assertEquals(StatusExecucaoAcao.NAO_INICIADA, source.getAcaoPdi().getStatusExecucao());
        assertNull(source.getAcaoPdi().getIniciadaEm());
        assertSame(manager, submitted.getDecididoPor());
        assertNotNull(submitted.getDecididaEm());
        assertEquals("Conforme", submitted.getJustificativaDecisao());
        assertNull(result.novaVersaoId());
        assertEquals(21L, result.versaoDecidida().versaoVigenteId());
        var event = ArgumentCaptor.forClass(PdiEvento.class);
        verify(eventos).saveAndFlush(event.capture());
        assertEquals("PDI_APROVADO", event.getValue().getTipo());
        assertSame(manager, event.getValue().getAutor());
        assertEquals(21L, event.getValue().getPdiVersaoId());
        assertEquals("AGUARDANDO_APROVACAO", event.getValue().getSnapshot().path("statusAprovacao").asText());
        verifyNoInteractions(acoes, conteudos);
    }

    @Test
    void approvalAllowsMissingReason() {
        service.aprovar(11L, 21L, null);
        assertNull(submitted.getJustificativaDecisao());
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", " \t\r\n ", "\u00a0", "\u2007"})
    void rejectionRequiresNonBlankReasonWithoutAnyPersistence(String reason) {
        assertThrows(IllegalArgumentException.class,
                () -> service.reprovar(11L, 21L, new PdiDecisaoRequest(reason)));
        verifyNoWrites();
    }

    @Test
    void rejectionPreservesSubmittedContentAndCreatesIndependentEditableVersionAndActions() {
        source.getAcaoPdi().setStatusExecucao(StatusExecucaoAcao.EM_EXECUCAO);
        source.getAcaoPdi().setIniciadaEm(Instant.parse("2026-02-01T00:00:00Z"));
        var result = service.reprovar(11L, 21L, new PdiDecisaoRequest("  Detalhar criterios  "));
        var versions = ArgumentCaptor.forClass(PdiVersao.class);
        verify(versoes, times(2)).saveAndFlush(versions.capture());
        PdiVersao revision = versions.getAllValues().get(1);
        assertSame(submitted, versions.getAllValues().get(0));
        assertEquals(StatusAprovacaoPdi.REPROVADO, submitted.getStatusAprovacao());
        assertEquals("Plano submetido", submitted.getTitulo());
        assertEquals("Objetivo preservado", submitted.getObjetivo());
        assertEquals(1, submitted.getNumero());
        assertEquals(Instant.parse("2026-01-01T00:00:00Z"), submitted.getSubmetidaEm());
        assertSame(manager, submitted.getGestorNaSubmissao());
        assertEquals("Detalhar criterios", submitted.getJustificativaDecisao());
        assertEquals(2, revision.getNumero());
        assertEquals(StatusAprovacaoPdi.RASCUNHO, revision.getStatusAprovacao());
        assertEquals(submitted.getTitulo(), revision.getTitulo());
        assertEquals(submitted.getObjetivo(), revision.getObjetivo());
        assertEquals(submitted.getInicioPlanejado(), revision.getInicioPlanejado());
        assertNull(revision.getSubmetidaEm());
        assertNull(revision.getGestorNaSubmissao());
        assertNull(revision.getDecididaEm());
        assertNull(revision.getDecididoPor());
        assertNull(revision.getJustificativaDecisao());
        assertNull(pdi.getVersaoVigenteId());
        assertEquals(22L, result.novaVersaoId());
        assertEquals(2, result.novoNumero());
        var copies = ArgumentCaptor.forClass(AcaoPdiVersao.class);
        verify(conteudos).saveAndFlush(copies.capture());
        AcaoPdiVersao copy = copies.getValue();
        assertNotSame(source, copy);
        assertNotSame(source.getAcaoPdi(), copy.getAcaoPdi());
        assertEquals(11L, copy.getPdiId());
        assertEquals(22L, copy.getPdiVersaoId());
        assertEquals(32L, copy.getAcaoPdiId());
        assertEquals(source.getTitulo(), copy.getTitulo());
        assertEquals(source.getObjetivo(), copy.getObjetivo());
        assertEquals(source.getResultadoEsperado(), copy.getResultadoEsperado());
        assertEquals(source.getInicioPlanejado(), copy.getInicioPlanejado());
        assertEquals(source.getFimPlanejado(), copy.getFimPlanejado());
        assertEquals(7, copy.getOrdem());
        assertEquals(StatusExecucaoAcao.NAO_INICIADA, copy.getAcaoPdi().getStatusExecucao());
        assertNull(copy.getAcaoPdi().getIniciadaEm());
        assertNull(copy.getAcaoPdi().getConcluidaEm());
        assertNull(copy.getAcaoPdi().getConcluidaPor());
        copy.setTitulo("Correcao independente");
        assertEquals("Acao original", source.getTitulo());
        assertEquals(StatusExecucaoAcao.EM_EXECUCAO, source.getAcaoPdi().getStatusExecucao());
        var history = ArgumentCaptor.forClass(PdiEvento.class);
        verify(eventos, times(2)).saveAndFlush(history.capture());
        assertEquals(List.of("PDI_REPROVADO", "REVISAO_CRIADA"),
                history.getAllValues().stream().map(PdiEvento::getTipo).toList());
        var mapping = history.getAllValues().get(1).getDados().path("acoesCopiadas").get(0);
        assertEquals(31L, mapping.path("acaoOrigemId").asLong());
        assertEquals(41L, mapping.path("acaoVersaoOrigemId").asLong());
        assertEquals(32L, mapping.path("acaoDestinoId").asLong());
        assertEquals(42L, mapping.path("acaoVersaoDestinoId").asLong());
        var order = inOrder(versoes, acoes);
        order.verify(versoes).saveAndFlush(submitted);
        order.verify(versoes).proximoNumero(11L);
        order.verify(versoes).saveAndFlush(revision);
        order.verify(acoes).saveAndFlush(copy.getAcaoPdi());
    }

    @Test
    void alreadyDecidedVersionCannotReceiveSecondDecisionOrCreateAnotherRevision() {
        service.reprovar(11L, 21L, new PdiDecisaoRequest("Corrigir"));
        clearInvocations(versoes, pdis, eventos, acoes, conteudos);
        assertStatus(HttpStatus.CONFLICT, () -> service.aprovar(11L, 21L, null));
        assertStatus(HttpStatus.CONFLICT, () -> service.reprovar(11L, 21L, new PdiDecisaoRequest("Outra")));
        verifyNoWrites();
    }

    @Test
    void exactVersionAndPdiMustMatch() {
        assertThrows(PdiRascunhoNaoEncontradoException.class, () -> service.aprovar(11L, 999L, null));
        verifyNoWrites();
    }

    @Test
    void initialDraftCannotBeDecided() {
        submitted.setStatusAprovacao(StatusAprovacaoPdi.RASCUNHO);
        submitted.setSubmetidaEm(null);
        assertStatus(HttpStatus.CONFLICT, () -> service.aprovar(11L, 21L, null));
        verifyNoWrites();
    }

    @Test
    void ownerCannotApproveOrRejectOwnPdiEvenAsManager() {
        owner.setPerfilAcesso(PerfilUsuario.GESTOR);
        when(principal.obterIdUsuarioAutenticado()).thenReturn(OWNER);
        assertStatus(HttpStatus.FORBIDDEN, () -> service.aprovar(11L, 21L, null));
        assertStatus(HttpStatus.FORBIDDEN, () -> service.reprovar(11L, 21L, new PdiDecisaoRequest("Corrigir")));
        verifyNoWrites();
    }

    @Test
    void oldManagerCannotDecideAfterHierarchyChange() {
        owner.setGestor(rh);
        assertThrows(PdiRascunhoNaoEncontradoException.class, () -> service.aprovar(11L, 21L, null));
        verifyNoWrites();
    }

    @Test
    void rhDecisionRequiresExplicitConfiguration() {
        when(principal.obterIdUsuarioAutenticado()).thenReturn(RH);
        assertStatus(HttpStatus.FORBIDDEN, () -> service.aprovar(11L, 21L, null));
        verifyNoWrites();
        service = service(RH.toString());
        service.aprovar(11L, 21L, null);
        assertSame(rh, submitted.getDecididoPor());
    }

    @Test
    void copyFailurePropagatesInsteadOfReturningPartialSuccess() {
        var failure = new IllegalStateException("synthetic copy failure");
        doThrow(failure).when(conteudos).saveAndFlush(any(AcaoPdiVersao.class));
        assertSame(failure, assertThrows(IllegalStateException.class,
                () -> service.reprovar(11L, 21L, new PdiDecisaoRequest("Corrigir"))));
        verify(conteudos).saveAndFlush(any(AcaoPdiVersao.class));
        verifyNoInteractions(eventos);
        // Mockito cannot prove rollback; the PostgreSQL integration verifies persisted atomicity.
    }

    @Test
    void auditFailurePropagatesInsteadOfReportingSuccessfulDecision() {
        when(eventos.saveAndFlush(any())).thenThrow(new IllegalStateException("synthetic audit failure"));
        assertThrows(IllegalStateException.class, () -> service.aprovar(11L, 21L, null));
    }

    @Test
    void rejectionAuditFailurePropagatesForTransactionalRollback() {
        when(eventos.saveAndFlush(any())).thenAnswer(call -> {
            PdiEvento event = call.getArgument(0);
            if ("REVISAO_CRIADA".equals(event.getTipo())) throw new IllegalStateException("synthetic audit failure");
            return event;
        });
        assertThrows(IllegalStateException.class,
                () -> service.reprovar(11L, 21L, new PdiDecisaoRequest("Corrigir")));
        verify(eventos, times(2)).saveAndFlush(any());
    }

    @Test
    void versionListHidesUnsubmittedRevisionFromManager() {
        PdiVersao revision = new PdiVersao();
        ReflectionTestUtils.setField(revision, "id", 22L);
        revision.setPdi(pdi);
        revision.setNumero(2);
        when(versoes.listarVersoes(11L)).thenReturn(List.of(revision, submitted));
        var visible = service.listarVersoes(11L);
        assertEquals(1, visible.size());
        assertEquals(21L, visible.get(0).versaoId());
        when(principal.obterIdUsuarioAutenticado()).thenReturn(OWNER);
        assertEquals(2, service.listarVersoes(11L).size());
    }

    private PdiService service(String allowedRh) {
        return new PdiService(pdis, versoes, conteudos, eventos, usuarios, autenticacao,
                new ObjectMapper().findAndRegisterModules(), acoes,
                new PdiAcessoService(usuarios, pdis, versoes, em, autenticacao, allowedRh));
    }

    private Optional<Usuario> userFor(UUID id) {
        return OWNER.equals(id) ? Optional.of(owner) : MANAGER.equals(id)
                ? Optional.of(manager) : RH.equals(id) ? Optional.of(rh) : Optional.empty();
    }

    private static Usuario user(UUID id, PerfilUsuario profile) {
        Usuario user = new Usuario();
        ReflectionTestUtils.setField(user, "id", id);
        user.setPerfilAcesso(profile);
        return user;
    }

    private void verifyNoWrites() {
        verify(versoes, never()).saveAndFlush(any());
        verify(pdis, never()).saveAndFlush(any());
        verifyNoInteractions(acoes, conteudos, eventos);
    }

    private static void assertStatus(HttpStatus expected, org.junit.jupiter.api.function.Executable operation) {
        assertEquals(expected, assertThrows(ResponseStatusException.class, operation).getStatusCode());
    }
}
