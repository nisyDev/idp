package com.empresa.pdi.service;

import com.empresa.pdi.dto.PdiSubmetidoResponse;
import com.empresa.pdi.enums.*;
import com.empresa.pdi.exception.PdiRascunhoNaoEditavelException;
import com.empresa.pdi.exception.PdiRascunhoNaoEncontradoException;
import com.empresa.pdi.model.*;
import com.empresa.pdi.repository.*;
import com.empresa.pdi.security.UsuarioAutenticadoProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** G7 regression and revised proposal submission; no Spring, JDBC, Flyway or Neon. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PdiSubmissaoServiceTest {
    private static final UUID TITULAR_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID GESTOR_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final long PDI_ID = 9001L;
    private static final long VERSAO_ID = 9002L;
    @Mock PDIRepository pdis;
    @Mock PdiVersaoRepository versoes;
    @Mock AcaoPdiVersaoRepository acoesVersoes;
    @Mock AcaoPDIRepository acoes;
    @Mock PdiEventoRepository eventos;
    @Mock UsuarioRepository usuarios;
    @Mock EntityManager em;
    @Mock ObjectProvider<UsuarioAutenticadoProvider> autenticacao;
    @Mock UsuarioAutenticadoProvider principal;
    private PdiService service;
    private Usuario titular;
    private Usuario gestor;
    private PDI pdi;
    private PdiVersao versao;

    @BeforeEach
    void configurar() {
        var acesso = new PdiAcessoService(usuarios, pdis, versoes, em, autenticacao, "");
        service = new PdiService(pdis, versoes, acoesVersoes, eventos, usuarios,
                autenticacao, new ObjectMapper().findAndRegisterModules(), acoes, acesso);
        titular = usuario(TITULAR_ID, PerfilUsuario.COLABORADOR);
        gestor = usuario(GESTOR_ID, PerfilUsuario.GESTOR);
        titular.setGestor(gestor);
        pdi = new PDI();
        ReflectionTestUtils.setField(pdi, "id", PDI_ID);
        pdi.setUsuario(titular);
        versao = new PdiVersao();
        ReflectionTestUtils.setField(versao, "id", VERSAO_ID);
        versao.setPdi(pdi);
        versao.setNumero(1);
        when(autenticacao.getIfAvailable()).thenReturn(principal);
        when(principal.obterIdUsuarioAutenticado()).thenReturn(TITULAR_ID);
        when(usuarios.findById(TITULAR_ID)).thenReturn(Optional.of(titular));
        when(usuarios.findByIdForUpdate(TITULAR_ID)).thenReturn(Optional.of(titular));
        when(usuarios.findByIdForUpdate(GESTOR_ID)).thenReturn(Optional.of(gestor));
        when(usuarios.buscarGestorId(TITULAR_ID)).thenAnswer(call -> titular.getGestor() == null
                ? Optional.empty() : Optional.of(titular.getGestor().getId()));
        when(pdis.buscarTitularId(PDI_ID)).thenReturn(Optional.of(TITULAR_ID));
        when(pdis.buscarDoTitularParaAtualizacao(PDI_ID, TITULAR_ID)).thenReturn(Optional.of(pdi));
        when(versoes.buscarEditavelParaAtualizacao(PDI_ID)).thenReturn(Optional.of(versao));
        when(acoesVersoes.contarNaVersao(PDI_ID, VERSAO_ID)).thenReturn(1L);
    }

    @Test
    void submeteVersaoInicialComAcaoEGestorAtivoEAuditaSemPromoverVigente() {
        PdiSubmetidoResponse resultado = service.submeter(PDI_ID);
        assertEquals(StatusAprovacaoPdi.AGUARDANDO_APROVACAO, resultado.statusAprovacao());
        assertEquals(VERSAO_ID, resultado.versaoId());
        assertEquals(GESTOR_ID, resultado.gestorNaSubmissaoId());
        assertNotNull(resultado.submetidaEm());
        assertNull(pdi.getVersaoVigenteId());
        assertEquals(StatusExecucaoPdi.NAO_INICIADO, pdi.getStatusExecucao());
        ArgumentCaptor<PdiEvento> captor = ArgumentCaptor.forClass(PdiEvento.class);
        verify(eventos).saveAndFlush(captor.capture());
        PdiEvento evento = captor.getValue();
        assertEquals("PDI_SUBMETIDO", evento.getTipo());
        assertEquals(PDI_ID, evento.getPdiId());
        assertEquals(VERSAO_ID, evento.getPdiVersaoId());
        assertSame(titular, evento.getAutor());
        assertEquals("AGUARDANDO_APROVACAO", evento.getDados().path("statusAprovacao").asText());
        assertEquals("RASCUNHO", evento.getSnapshot().path("statusAprovacao").asText());
        var ordem = inOrder(usuarios, pdis, versoes);
        ordem.verify(usuarios).findByIdForUpdate(TITULAR_ID);
        ordem.verify(usuarios).findByIdForUpdate(GESTOR_ID);
        ordem.verify(pdis).buscarDoTitularParaAtualizacao(PDI_ID, TITULAR_ID);
        ordem.verify(versoes).buscarEditavelParaAtualizacao(PDI_ID);
    }

    @Test
    void bloqueiaSubmissaoSemAcoesIncluindoTodasRemovidas() {
        when(acoesVersoes.contarNaVersao(PDI_ID, VERSAO_ID)).thenReturn(0L);
        assertStatus(HttpStatus.CONFLICT, () -> service.submeter(PDI_ID));
        semPersistencia();
    }

    @Test
    void bloqueiaSubmissaoSemGestor() {
        titular.setGestor(null);
        assertStatus(HttpStatus.CONFLICT, () -> service.submeter(PDI_ID));
        semPersistencia();
    }

    @Test
    void bloqueiaSubmissaoQuandoGestorInativo() {
        gestor.setAtivo(false);
        assertStatus(HttpStatus.CONFLICT, () -> service.submeter(PDI_ID));
        semPersistencia();
    }

    @Test
    void impedeEnvioDuplicado() {
        versao.setStatusAprovacao(StatusAprovacaoPdi.AGUARDANDO_APROVACAO);
        versao.setSubmetidaEm(Instant.now());
        assertThrows(PdiRascunhoNaoEditavelException.class, () -> service.submeter(PDI_ID));
        semPersistencia();
    }

    @Test
    void permiteRessubmeterNovaVersaoComIdentificadorExplicito() {
        versao.setNumero(2);
        // Rejected versions remain in history; global submission counts no longer prohibit a revision.
        when(versoes.contarVersoesSubmetidas(PDI_ID)).thenReturn(1L);
        PdiSubmetidoResponse result = service.submeter(PDI_ID, VERSAO_ID);
        assertEquals(2, result.numero());
        assertEquals(StatusAprovacaoPdi.AGUARDANDO_APROVACAO, result.statusAprovacao());
        verify(versoes, never()).contarVersoesSubmetidas(PDI_ID);
    }

    @Test
    void requisicaoAntigaSemVersaoNaoPodeSubmeterRevisao() {
        versao.setNumero(2);
        assertThrows(PdiRascunhoNaoEditavelException.class, () -> service.submeter(PDI_ID));
        semPersistencia();
    }

    @Test
    void identificadorDiferenteNaoSubmeteVersaoAtual() {
        assertThrows(PdiRascunhoNaoEditavelException.class, () -> service.submeter(PDI_ID, 999L));
        semPersistencia();
    }

    @Test
    void versaoHistoricaNaoPodeSerSubmetidaNovamente() {
        versao.setStatusAprovacao(StatusAprovacaoPdi.REPROVADO);
        versao.setSubmetidaEm(Instant.now());
        assertThrows(PdiRascunhoNaoEditavelException.class, () -> service.submeter(PDI_ID, VERSAO_ID));
        semPersistencia();
    }

    @Test
    void naoPermiteSubmeterPdiForaDaTitularidadeMesmoSemGestor() {
        titular.setGestor(null);
        when(pdis.buscarTitularId(PDI_ID)).thenReturn(Optional.empty());
        when(pdis.buscarDoTitularParaAtualizacao(PDI_ID, TITULAR_ID)).thenReturn(Optional.empty());
        assertThrows(PdiRascunhoNaoEncontradoException.class, () -> service.submeter(PDI_ID));
        semPersistencia();
    }

    @Test
    void listaSubmetidosSomenteDoTitularAtivo() {
        versao.setSubmetidaEm(Instant.now());
        versao.setStatusAprovacao(StatusAprovacaoPdi.REPROVADO);
        versao.setGestorNaSubmissao(gestor);
        when(versoes.listarSubmetidos(TITULAR_ID)).thenReturn(List.of(versao));
        var resultado = service.listarSubmetidos();
        assertEquals(1, resultado.size());
        assertEquals(StatusAprovacaoPdi.REPROVADO, resultado.get(0).statusAprovacao());
        verify(versoes).listarSubmetidos(TITULAR_ID);
        verifyNoInteractions(eventos);
    }

    @Test
    void bloqueiaListagemQuandoTitularInativo() {
        titular.setAtivo(false);
        assertThrows(BadCredentialsException.class, () -> service.listarSubmetidos());
        verify(versoes, never()).listarSubmetidos(any());
    }

    private void semPersistencia() {
        verify(versoes, never()).saveAndFlush(any());
        verifyNoInteractions(eventos, acoes);
    }

    private static Usuario usuario(UUID id, PerfilUsuario perfil) {
        Usuario usuario = new Usuario();
        ReflectionTestUtils.setField(usuario, "id", id);
        usuario.setPerfilAcesso(perfil);
        return usuario;
    }

    private static void assertStatus(HttpStatus expected, org.junit.jupiter.api.function.Executable operation) {
        assertEquals(expected, assertThrows(ResponseStatusException.class, operation).getStatusCode());
    }
}
