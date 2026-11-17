package com.empresa.pdi.service;

import com.empresa.pdi.dto.PdiSubmetidoResponse;
import com.empresa.pdi.enums.StatusAprovacaoPdi;
import com.empresa.pdi.exception.PdiRascunhoNaoEditavelException;
import com.empresa.pdi.exception.PdiRascunhoNaoEncontradoException;
import com.empresa.pdi.model.PDI;
import com.empresa.pdi.model.PdiEvento;
import com.empresa.pdi.model.PdiVersao;
import com.empresa.pdi.model.Usuario;
import com.empresa.pdi.repository.AcaoPdiVersaoRepository;
import com.empresa.pdi.repository.PDIRepository;
import com.empresa.pdi.repository.PdiEventoRepository;
import com.empresa.pdi.repository.PdiVersaoRepository;
import com.empresa.pdi.repository.UsuarioRepository;
import com.empresa.pdi.security.UsuarioAutenticadoProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Pure Mockito unit tests: no Spring context, DataSource, Flyway or Neon connection. */
@ExtendWith(MockitoExtension.class)
class PdiSubmissaoServiceTest {
    private static final UUID TITULAR_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID GESTOR_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final long PDI_ID = 9001L;
    private static final long VERSAO_ID = 9002L;

    @Mock private PDIRepository pdis;
    @Mock private PdiVersaoRepository versoes;
    @Mock private AcaoPdiVersaoRepository acoesVersoes;
    @Mock private PdiEventoRepository eventos;
    @Mock private UsuarioRepository usuarios;
    @Mock private ObjectProvider<UsuarioAutenticadoProvider> autenticacao;
    @Mock private UsuarioAutenticadoProvider principal;

    private PdiService service;

    @BeforeEach
    void configurar() {
        service = new PdiService(pdis, versoes, acoesVersoes, eventos, usuarios,
                autenticacao, new ObjectMapper());
        when(autenticacao.getIfAvailable()).thenReturn(principal);
        when(principal.obterIdUsuarioAutenticado()).thenReturn(TITULAR_ID);
    }

    @Test
    void submeteRascunhoComAcaoEGestorAtivoEAudita() {
        Cenario cenario = prepararRascunho();
        cenario.titular().setGestor(cenario.gestor());
        when(acoesVersoes.contarNaVersao(PDI_ID, VERSAO_ID)).thenReturn(1L);
        when(usuarios.findByIdForUpdate(GESTOR_ID)).thenReturn(Optional.of(cenario.gestor()));

        PdiSubmetidoResponse resultado = service.submeter(PDI_ID);

        assertEquals(StatusAprovacaoPdi.AGUARDANDO_APROVACAO, resultado.statusAprovacao());
        assertEquals(Long.valueOf(PDI_ID), resultado.id());
        assertEquals(Long.valueOf(VERSAO_ID), resultado.versaoId());
        assertEquals(GESTOR_ID, resultado.gestorNaSubmissaoId());
        assertNotNull(resultado.submetidaEm());
        assertEquals(StatusAprovacaoPdi.AGUARDANDO_APROVACAO, cenario.versao().getStatusAprovacao());
        assertEquals(resultado.submetidaEm(), cenario.versao().getSubmetidaEm());
        assertNull(cenario.pdi().getVersaoVigenteId(), "Submissao nao equivale a aprovacao");
        verify(versoes).saveAndFlush(same(cenario.versao()));

        ArgumentCaptor<PdiEvento> auditoria = ArgumentCaptor.forClass(PdiEvento.class);
        verify(eventos).saveAndFlush(auditoria.capture());
        PdiEvento evento = auditoria.getValue();
        assertEquals("PDI_SUBMETIDO", evento.getTipo());
        assertEquals(Long.valueOf(PDI_ID), evento.getPdiId());
        assertEquals(Long.valueOf(VERSAO_ID), evento.getPdiVersaoId());
        assertSame(cenario.titular(), evento.getAutor());
        assertEquals("AGUARDANDO_APROVACAO", evento.getDados().path("statusAprovacao").asText());
        assertEquals(GESTOR_ID.toString(), evento.getDados().path("gestorNaSubmissaoId").asText());
        assertEquals("RASCUNHO", evento.getSnapshot().path("statusAprovacao").asText());
        assertTrue(evento.getSnapshot().path("submetidaEm").isNull());

        // The lock order must match draft/action write operations.
        InOrder ordem = inOrder(usuarios, pdis, versoes);
        ordem.verify(usuarios).findByIdForUpdate(TITULAR_ID);
        ordem.verify(pdis).buscarDoTitularParaAtualizacao(PDI_ID, TITULAR_ID);
        ordem.verify(versoes).buscarInicialParaAtualizacao(PDI_ID);
        ordem.verify(usuarios).findByIdForUpdate(GESTOR_ID);
    }

    @Test
    void bloqueiaSubmissaoSemAcoes() {
        prepararRascunho();
        // Mock returns zero for an unstubbed primitive long.
        ResponseStatusException erro = assertThrows(ResponseStatusException.class,
                () -> service.submeter(PDI_ID));
        assertEquals(HttpStatus.CONFLICT, erro.getStatusCode());
        verify(acoesVersoes).contarNaVersao(PDI_ID, VERSAO_ID);
        verificarSemPersistencia();
    }

    @Test
    void bloqueiaSubmissaoSemGestor() {
        prepararRascunho();
        when(acoesVersoes.contarNaVersao(PDI_ID, VERSAO_ID)).thenReturn(1L);
        ResponseStatusException erro = assertThrows(ResponseStatusException.class,
                () -> service.submeter(PDI_ID));
        assertEquals(HttpStatus.CONFLICT, erro.getStatusCode());
        verificarSemPersistencia();
    }

    @Test
    void bloqueiaSubmissaoQuandoGestorInativo() {
        Cenario cenario = prepararRascunho();
        cenario.titular().setGestor(cenario.gestor());
        cenario.gestor().setAtivo(false);
        when(acoesVersoes.contarNaVersao(PDI_ID, VERSAO_ID)).thenReturn(1L);
        when(usuarios.findByIdForUpdate(GESTOR_ID)).thenReturn(Optional.of(cenario.gestor()));

        ResponseStatusException erro = assertThrows(ResponseStatusException.class,
                () -> service.submeter(PDI_ID));
        assertEquals(HttpStatus.CONFLICT, erro.getStatusCode());
        verificarSemPersistencia();
    }

    @Test
    void impedeEnvioDuplicadoDeVersaoSubmetida() {
        Cenario cenario = prepararRascunho();
        cenario.versao().setStatusAprovacao(StatusAprovacaoPdi.AGUARDANDO_APROVACAO);
        cenario.versao().setSubmetidaEm(Instant.now());

        assertThrows(PdiRascunhoNaoEditavelException.class, () -> service.submeter(PDI_ID));
        verifyNoInteractions(acoesVersoes);
        verificarSemPersistencia();
    }

    @Test
    void impedeEnvioQuandoJaHaVersaoSubmetidaAnteriormente() {
        prepararRascunho();
        when(versoes.contarVersoesSubmetidas(PDI_ID)).thenReturn(1L);

        assertThrows(PdiRascunhoNaoEditavelException.class, () -> service.submeter(PDI_ID));
        verifyNoInteractions(acoesVersoes);
        verificarSemPersistencia();
    }

    @Test
    void naoPermiteSubmeterPdiForaDaTitularidade() {
        Cenario cenario = novoCenario();
        when(usuarios.findByIdForUpdate(TITULAR_ID)).thenReturn(Optional.of(cenario.titular()));
        when(pdis.buscarDoTitularParaAtualizacao(PDI_ID, TITULAR_ID)).thenReturn(Optional.empty());

        assertThrows(PdiRascunhoNaoEncontradoException.class, () -> service.submeter(PDI_ID));
        verifyNoInteractions(versoes, acoesVersoes);
        verificarSemPersistencia();
    }

    @Test
    void listaSubmetidosSomenteComIdentidadeDoTitularAtivo() {
        Cenario cenario = novoCenario();
        cenario.versao().setSubmetidaEm(Instant.now());
        cenario.versao().setStatusAprovacao(StatusAprovacaoPdi.AGUARDANDO_APROVACAO);
        cenario.versao().setGestorNaSubmissao(cenario.gestor());
        when(usuarios.findById(TITULAR_ID)).thenReturn(Optional.of(cenario.titular()));
        when(versoes.listarSubmetidos(TITULAR_ID)).thenReturn(List.of(cenario.versao()));

        List<PdiSubmetidoResponse> resultado = service.listarSubmetidos();

        assertEquals(1, resultado.size());
        assertEquals(Long.valueOf(PDI_ID), resultado.get(0).id());
        assertEquals(StatusAprovacaoPdi.AGUARDANDO_APROVACAO, resultado.get(0).statusAprovacao());
        assertEquals(GESTOR_ID, resultado.get(0).gestorNaSubmissaoId());
        verify(versoes).listarSubmetidos(TITULAR_ID);
        verifyNoInteractions(eventos, acoesVersoes);
    }

    @Test
    void bloqueiaListagemQuandoTitularEstaInativo() {
        Cenario cenario = novoCenario();
        cenario.titular().setAtivo(false);
        when(usuarios.findById(TITULAR_ID)).thenReturn(Optional.of(cenario.titular()));

        assertThrows(org.springframework.security.authentication.BadCredentialsException.class,
                () -> service.listarSubmetidos());
        verify(versoes, never()).listarSubmetidos(any());
    }

    private Cenario novoCenario() {
        Usuario titular = new Usuario();
        ReflectionTestUtils.setField(titular, "id", TITULAR_ID);
        titular.setAtivo(true);
        Usuario gestor = new Usuario();
        ReflectionTestUtils.setField(gestor, "id", GESTOR_ID);
        gestor.setAtivo(true);
        PDI pdi = new PDI();
        ReflectionTestUtils.setField(pdi, "id", PDI_ID);
        pdi.setUsuario(titular);
        PdiVersao versao = new PdiVersao();
        ReflectionTestUtils.setField(versao, "id", VERSAO_ID);
        versao.setPdi(pdi);
        versao.setNumero(1);
        versao.setStatusAprovacao(StatusAprovacaoPdi.RASCUNHO);
        return new Cenario(titular, gestor, pdi, versao);
    }

    private Cenario prepararRascunho() {
        Cenario cenario = novoCenario();
        when(usuarios.findByIdForUpdate(TITULAR_ID)).thenReturn(Optional.of(cenario.titular()));
        when(pdis.buscarDoTitularParaAtualizacao(PDI_ID, TITULAR_ID))
                .thenReturn(Optional.of(cenario.pdi()));
        when(versoes.buscarInicialParaAtualizacao(PDI_ID)).thenReturn(Optional.of(cenario.versao()));
        return cenario;
    }

    private void verificarSemPersistencia() {
        verify(versoes, never()).saveAndFlush(any(PdiVersao.class));
        verify(eventos, never()).saveAndFlush(any(PdiEvento.class));
    }

    private record Cenario(Usuario titular, Usuario gestor, PDI pdi, PdiVersao versao) {
    }
}
