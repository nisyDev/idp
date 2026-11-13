package com.empresa.pdi.service;

import com.empresa.pdi.dto.AcaoPdiResponse;
import com.empresa.pdi.dto.AtualizarAcaoPdiRequest;
import com.empresa.pdi.enums.PerfilUsuario;
import com.empresa.pdi.enums.StatusAprovacaoPdi;
import com.empresa.pdi.enums.StatusExecucaoAcao;
import com.empresa.pdi.enums.StatusExecucaoPdi;
import com.empresa.pdi.exception.PdiRascunhoNaoEditavelException;
import com.empresa.pdi.exception.PdiRascunhoNaoEncontradoException;
import com.empresa.pdi.model.AcaoPDI;
import com.empresa.pdi.model.AcaoPdiVersao;
import com.empresa.pdi.model.PDI;
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
import java.lang.reflect.Modifier;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Unit tests only: no Spring context, datasource, JPA bootstrap or database callbacks. */
@ExtendWith(MockitoExtension.class)
class AcaoPdiServiceTest {
    private static final UUID TITULAR_ID = new UUID(0, 17);
    private static final UUID OUTRO_ID = new UUID(0, 18);
    private static final Long PDI_ID = 101L;
    private static final Long VERSAO_ID = 201L;
    private static final Long ACAO_ID = 301L;
    private static final Long CONTEUDO_ID = 401L;
    private static final LocalDate INICIO = LocalDate.of(2026, 2, 1);
    private static final LocalDate FIM = LocalDate.of(2026, 3, 15);
    private static final Set<String> CAMPOS_EDITAVEIS = Set.of(
            "titulo", "objetivo", "inicioPlanejado", "fimPlanejado", "resultadoEsperado");

    @Mock AcaoPDIRepository acoes;
    @Mock AcaoPdiVersaoRepository acoesVersoes;
    @Mock PDIRepository pdis;
    @Mock PdiVersaoRepository versoes;
    @Mock PdiEventoRepository eventos;
    @Mock UsuarioRepository usuarios;
    @Mock ObjectProvider<UsuarioAutenticadoProvider> autenticacao;

    private final ObjectMapper mapper = new ObjectMapper();
    private AcaoPdiService service;
    private Usuario titular;
    private PDI pdi;
    private PdiVersao versao;
    private AcaoPDI acao;
    private AcaoPdiVersao conteudo;

    @BeforeEach
    void configurar() {
        service = new AcaoPdiService(acoes, acoesVersoes, pdis, versoes, eventos,
                usuarios, autenticacao, mapper);
        titular = new Usuario();
        ReflectionTestUtils.setField(titular, "id", TITULAR_ID);
        titular.setNome("Titular");
        titular.setEmail("titular@example.test");
        titular.setPerfilAcesso(PerfilUsuario.COLABORADOR);
        pdi = new PDI();
        ReflectionTestUtils.setField(pdi, "id", PDI_ID);
        ReflectionTestUtils.setField(pdi, "lockVersion", 7L);
        ReflectionTestUtils.setField(pdi, "criadoEm", Instant.parse("2026-01-01T12:00:00Z"));
        pdi.setUsuario(titular);
        versao = new PdiVersao();
        ReflectionTestUtils.setField(versao, "id", VERSAO_ID);
        ReflectionTestUtils.setField(versao, "criadaEm", Instant.parse("2026-01-01T12:01:00Z"));
        versao.setPdi(pdi);
        versao.setNumero(1);
        versao.setTitulo("Plano original");
        versao.setObjetivo("Objetivo do plano");
        versao.setInicioPlanejado(INICIO);
        versao.setFimPlanejado(FIM);
        acao = new AcaoPDI();
        ReflectionTestUtils.setField(acao, "id", ACAO_ID);
        acao.setPdi(pdi);
        AcaoPdiVersao original = new AcaoPdiVersao();
        ReflectionTestUtils.setField(original, "id", CONTEUDO_ID);
        original.definirVinculos(versao, acao);
        original.setOrdem(9);
        original.setTitulo("Titulo anterior");
        original.setObjetivo("Objetivo anterior");
        original.setInicioPlanejado(INICIO);
        original.setFimPlanejado(FIM);
        original.setResultadoEsperado("Resultado anterior");
        conteudo = spy(original);
    }

    @Test
    void substituiCincoCamposPreservaDemaisDadosEVinculosERetornaResponseCompleta() {
        prepararEdicao();
        var preservado = estadoProtegido();
        var request = novosDados();

        AcaoPdiResponse response = service.atualizarRascunho(PDI_ID, ACAO_ID, request);

        assertEquals(request, dadosAtuais());
        assertEquals(preservado, estadoProtegido(), "Somente os cinco campos podem mudar");
        assertEquals(responseEsperada(request), response);
        assertAll(
                () -> assertSame(titular, pdi.getUsuario()),
                () -> assertSame(pdi, acao.getPdi()),
                () -> assertSame(pdi, versao.getPdi()),
                () -> assertSame(versao, conteudo.getPdiVersao()),
                () -> assertSame(acao, conteudo.getAcaoPdi()),
                () -> assertEquals(PDI_ID, conteudo.getPdiId()),
                () -> assertEquals(VERSAO_ID, conteudo.getPdiVersaoId()),
                () -> assertEquals(ACAO_ID, conteudo.getAcaoPdiId()),
                () -> assertEquals(CONTEUDO_ID, conteudo.getId()),
                () -> assertEquals(9, conteudo.getOrdem()),
                () -> assertEquals(StatusAprovacaoPdi.RASCUNHO, versao.getStatusAprovacao()),
                () -> assertEquals(StatusExecucaoPdi.NAO_INICIADO, pdi.getStatusExecucao()),
                () -> assertEquals(StatusExecucaoAcao.NAO_INICIADA, acao.getStatusExecucao()));
        var ordem = inOrder(usuarios, pdis, versoes, acoesVersoes, eventos);
        ordem.verify(usuarios).findByIdForUpdate(TITULAR_ID);
        ordem.verify(pdis).buscarDoTitularParaAtualizacao(PDI_ID, TITULAR_ID);
        ordem.verify(versoes).buscarInicialParaAtualizacao(PDI_ID);
        ordem.verify(versoes).contarVersoesSubmetidas(PDI_ID);
        ordem.verify(acoesVersoes).buscarNaVersao(PDI_ID, VERSAO_ID, ACAO_ID);
        ordem.verify(acoesVersoes).saveAndFlush(conteudo);
        ordem.verify(eventos).saveAndFlush(any(PdiEvento.class));
        ordem.verifyNoMoreInteractions();
        verifyNoInteractions(acoes);
    }

    @ParameterizedTest
    @ValueSource(strings = {"titulo", "objetivo", "inicioPlanejado", "fimPlanejado", "resultadoEsperado"})
    void alteracaoIsoladaDeCadaCampoContaComoUmaEdicao(String campo) {
        prepararEdicao();
        var anterior = dadosAtuais();
        var request = new AtualizarAcaoPdiRequest(
                campo.equals("titulo") ? "Novo titulo" : anterior.titulo(),
                campo.equals("objetivo") ? "Novo objetivo" : anterior.objetivo(),
                campo.equals("inicioPlanejado") ? INICIO.plusDays(1) : anterior.inicioPlanejado(),
                campo.equals("fimPlanejado") ? FIM.plusDays(1) : anterior.fimPlanejado(),
                campo.equals("resultadoEsperado") ? "Novo resultado" : anterior.resultadoEsperado());

        assertEquals(responseEsperada(request), service.atualizarRascunho(PDI_ID, ACAO_ID, request));

        assertEquals(request, dadosAtuais());
        verify(acoesVersoes).saveAndFlush(conteudo);
        var captor = ArgumentCaptor.forClass(PdiEvento.class);
        verify(eventos).saveAndFlush(captor.capture());
        assertEquals("ACAO_EDITADA", captor.getValue().getTipo());
        verifyNoMoreInteractions(eventos);
    }

    @Test
    void substituicaoIntegralAceitaLimparTodosOsCincoCamposComNull() throws Exception {
        prepararEdicao();
        var preservado = estadoProtegido();
        var request = new AtualizarAcaoPdiRequest(null, null, null, null, null);

        assertEquals(responseEsperada(request), service.atualizarRascunho(PDI_ID, ACAO_ID, request));

        assertEquals(request, dadosAtuais());
        assertEquals(preservado, estadoProtegido());
        var captor = ArgumentCaptor.forClass(PdiEvento.class);
        verify(eventos).saveAndFlush(captor.capture());
        assertEquals(mapper.readTree("""
                {"titulo":null,"objetivo":null,"inicioPlanejado":null,
                 "fimPlanejado":null,"resultadoEsperado":null}
                """), captor.getValue().getDados());
        assertEquals(mapper.readTree("""
                {"titulo":"Titulo anterior","objetivo":"Objetivo anterior",
                 "inicioPlanejado":"2026-02-01","fimPlanejado":"2026-03-15",
                 "resultadoEsperado":"Resultado anterior"}
                """), captor.getValue().getSnapshot());
    }

    @ParameterizedTest
    @MethodSource("datasPermitidas")
    void aceitaDatasIguaisOuApenasUmLimite(LocalDate inicio, LocalDate fim) {
        prepararEdicao();
        var request = new AtualizarAcaoPdiRequest("Titulo", "Objetivo", inicio, fim, "Resultado");

        assertEquals(responseEsperada(request), service.atualizarRascunho(PDI_ID, ACAO_ID, request));

        assertEquals(request, dadosAtuais());
        verify(eventos).saveAndFlush(any(PdiEvento.class));
    }

    static Stream<Arguments> datasPermitidas() {
        return Stream.of(Arguments.of(INICIO, INICIO), Arguments.of(INICIO, null),
                Arguments.of(null, FIM));
    }

    @ParameterizedTest
    @MethodSource("conteudosInalterados")
    void putInalteradoIncluindoNullEDatasNaoInvocaSettersNemPersistencia(AtualizarAcaoPdiRequest iguais) {
        aplicarDadosNaFixture(iguais);
        prepararConsultaConteudo();
        var antes = campos(conteudo, AcaoPdiVersao.class, Set.of());

        assertEquals(responseEsperada(iguais), service.atualizarRascunho(PDI_ID, ACAO_ID, iguais));

        assertEquals(antes, campos(conteudo, AcaoPdiVersao.class, Set.of()));
        assertSemMutacaoNemGravacao();
    }

    static Stream<AtualizarAcaoPdiRequest> conteudosInalterados() {
        return Stream.of(
                new AtualizarAcaoPdiRequest("Titulo anterior", "Objetivo anterior", INICIO, FIM, "Resultado anterior"),
                new AtualizarAcaoPdiRequest(null, null, null, null, null),
                new AtualizarAcaoPdiRequest("Titulo", null, INICIO, null, "Resultado"));
    }

    @Test
    void eventoCapturaCincoValoresAnterioresEPosterioresComNullIsoAutorEVinculos() throws Exception {
        aplicarDadosNaFixture(new AtualizarAcaoPdiRequest("Titulo anterior", null, INICIO, null, "Resultado anterior"));
        prepararEdicao();
        var request = new AtualizarAcaoPdiRequest(null, "Objetivo novo", null, FIM, null);

        service.atualizarRascunho(PDI_ID, ACAO_ID, request);

        var captor = ArgumentCaptor.forClass(PdiEvento.class);
        verify(eventos).saveAndFlush(captor.capture());
        verifyNoMoreInteractions(eventos);
        PdiEvento evento = captor.getValue();
        assertEquals(mapper.readTree("""
                {"titulo":"Titulo anterior","objetivo":null,"inicioPlanejado":"2026-02-01",
                 "fimPlanejado":null,"resultadoEsperado":"Resultado anterior"}
                """), evento.getSnapshot());
        assertEquals(mapper.readTree("""
                {"titulo":null,"objetivo":"Objetivo novo","inicioPlanejado":null,
                 "fimPlanejado":"2026-03-15","resultadoEsperado":null}
                """), evento.getDados());
        assertAll(
                () -> assertEquals(5, evento.getSnapshot().size()),
                () -> assertEquals(5, evento.getDados().size()),
                () -> assertEquals("ACAO_EDITADA", evento.getTipo()),
                () -> assertSame(titular, evento.getAutor()),
                () -> assertEquals(TITULAR_ID, evento.getAutor().getId()),
                () -> assertSame(pdi, evento.getPdi()),
                () -> assertSame(versao, evento.getPdiVersao()),
                () -> assertSame(acao, evento.getAcaoPdi()),
                () -> assertSame(conteudo, evento.getAcaoPdiVersao()),
                () -> assertEquals(PDI_ID, evento.getPdiId()),
                () -> assertEquals(VERSAO_ID, evento.getPdiVersaoId()),
                () -> assertEquals(ACAO_ID, evento.getAcaoPdiId()),
                () -> assertNull(evento.getComponente()),
                () -> assertNull(evento.getComponenteId()),
                () -> assertNull(evento.getSubmissao()),
                () -> assertNull(evento.getJustificativa()),
                // saveAndFlush is mocked: @PrePersist never ran and no persisted time is claimed.
                () -> assertNull(evento.getOcorridoEm()),
                () -> assertNull(evento.getId()));
        conteudo.setTitulo("Alteracao posterior fora da operacao");
        assertTrue(evento.getDados().get("titulo").isNull());
        assertEquals("Titulo anterior", evento.getSnapshot().get("titulo").textValue());
    }

    @Test
    void cadaEdicaoEfetivaAcrescentaUmEventoEPreservaOsEventosAnteriores() {
        prepararEdicao();
        PdiEvento anterior = new PdiEvento(versao, acao, conteudo, null, null, titular,
                "ACAO_ADICIONADA", null, mapper.createObjectNode().put("marcador", "original"), null);
        var dadosAnteriores = anterior.getDados();
        var registros = new ArrayList<>(List.of(anterior));
        when(eventos.saveAndFlush(any(PdiEvento.class))).thenAnswer(invocacao -> {
            PdiEvento novo = invocacao.getArgument(0);
            registros.add(novo);
            return novo;
        });
        var primeira = novosDados();
        var segunda = new AtualizarAcaoPdiRequest("Segunda edicao", null, null, FIM, null);

        service.atualizarRascunho(PDI_ID, ACAO_ID, primeira);
        service.atualizarRascunho(PDI_ID, ACAO_ID, primeira);
        service.atualizarRascunho(PDI_ID, ACAO_ID, segunda);

        assertEquals(3, registros.size());
        assertSame(anterior, registros.get(0));
        assertEquals("ACAO_ADICIONADA", anterior.getTipo());
        assertEquals(dadosAnteriores, anterior.getDados());
        assertNull(anterior.getSnapshot());
        assertEquals("ACAO_EDITADA", registros.get(1).getTipo());
        assertEquals("ACAO_EDITADA", registros.get(2).getTipo());
        assertEquals(registros.get(1).getDados(), registros.get(2).getSnapshot());
        verify(acoesVersoes, times(2)).saveAndFlush(conteudo);
        verify(eventos, times(2)).saveAndFlush(any(PdiEvento.class));
        verifyNoMoreInteractions(eventos);
    }

    @Test
    void providerAusenteImpedeOperacaoAntesDosRepositorios() {
        assertThrows(AuthenticationCredentialsNotFoundException.class,
                () -> service.atualizarRascunho(PDI_ID, ACAO_ID, novosDados()));
        verifyNoInteractions(usuarios, pdis, versoes, acoesVersoes, acoes, eventos);
    }

    @Test
    void identidadeNulaImpedeOperacaoAntesDosRepositorios() {
        when(autenticacao.getIfAvailable()).thenReturn(() -> null);
        assertThrows(AuthenticationCredentialsNotFoundException.class,
                () -> service.atualizarRascunho(PDI_ID, ACAO_ID, novosDados()));
        verifyNoInteractions(usuarios, pdis, versoes, acoesVersoes, acoes, eventos);
    }

    @Test
    void rejeicaoDoProviderEhPropagadaSemConsultarRepositorios() {
        var falha = new BadCredentialsException("Identidade invalida");
        UsuarioAutenticadoProvider provider = mock(UsuarioAutenticadoProvider.class);
        when(autenticacao.getIfAvailable()).thenReturn(provider);
        when(provider.obterIdUsuarioAutenticado()).thenThrow(falha);

        assertSame(falha, assertThrows(BadCredentialsException.class,
                () -> service.atualizarRascunho(PDI_ID, ACAO_ID, novosDados())));

        verifyNoInteractions(usuarios, pdis, versoes, acoesVersoes, acoes, eventos);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void usuarioInexistenteOuInativoNaoPodeEditar(boolean inexistente) {
        when(autenticacao.getIfAvailable()).thenReturn(() -> TITULAR_ID);
        titular.setAtivo(false);
        when(usuarios.findByIdForUpdate(TITULAR_ID))
                .thenReturn(inexistente ? Optional.empty() : Optional.of(titular));

        assertThrows(BadCredentialsException.class,
                () -> service.atualizarRascunho(PDI_ID, ACAO_ID, novosDados()));

        verifyNoInteractions(pdis, versoes, acoesVersoes, acoes, eventos);
        assertSemMutacaoNemGravacao();
    }

    @ParameterizedTest
    @EnumSource(PerfilUsuario.class)
    void titularidadeEhObrigatoriaInclusiveParaGestorERhAdmin(PerfilUsuario perfil) {
        Usuario outro = new Usuario();
        ReflectionTestUtils.setField(outro, "id", OUTRO_ID);
        outro.setPerfilAcesso(perfil);
        when(autenticacao.getIfAvailable()).thenReturn(() -> OUTRO_ID);
        when(usuarios.findByIdForUpdate(OUTRO_ID)).thenReturn(Optional.of(outro));
        when(pdis.buscarDoTitularParaAtualizacao(PDI_ID, OUTRO_ID)).thenReturn(Optional.empty());

        assertThrows(PdiRascunhoNaoEncontradoException.class,
                () -> service.atualizarRascunho(PDI_ID, ACAO_ID, novosDados()));

        assertSame(titular, pdi.getUsuario());
        verify(pdis).buscarDoTitularParaAtualizacao(PDI_ID, OUTRO_ID);
        verifyNoMoreInteractions(pdis);
        verifyNoInteractions(versoes, acoesVersoes, acoes, eventos);
        assertSemMutacaoNemGravacao();
    }

    @ParameterizedTest
    @MethodSource("identificadoresInvalidos")
    void identificadoresInvalidosNaoConsultamPdiNemModificamConteudo(Long pdiId, Long acaoId) {
        prepararTitular();

        assertThrows(PdiRascunhoNaoEncontradoException.class,
                () -> service.atualizarRascunho(pdiId, acaoId, novosDados()));

        verifyNoInteractions(pdis, versoes, acoesVersoes, acoes, eventos);
        assertSemMutacaoNemGravacao();
    }

    static Stream<Arguments> identificadoresInvalidos() {
        return Stream.of(Arguments.of(null, ACAO_ID), Arguments.of(0L, ACAO_ID),
                Arguments.of(-1L, ACAO_ID), Arguments.of(PDI_ID, null),
                Arguments.of(PDI_ID, 0L), Arguments.of(PDI_ID, -1L));
    }

    @Test
    void pdiInexistenteTemMesmaFalhaPrivadaDePdiInacessivel() {
        prepararTitular();
        when(pdis.buscarDoTitularParaAtualizacao(PDI_ID, TITULAR_ID)).thenReturn(Optional.empty());

        assertThrows(PdiRascunhoNaoEncontradoException.class,
                () -> service.atualizarRascunho(PDI_ID, ACAO_ID, novosDados()));

        verifyNoInteractions(versoes, acoesVersoes, acoes, eventos);
        assertSemMutacaoNemGravacao();
    }

    @Test
    void ausenciaDeVersaoInicialEhConflitoSemAlterarDados() {
        prepararPdi();
        when(versoes.buscarInicialParaAtualizacao(PDI_ID)).thenReturn(Optional.empty());

        assertThrows(PdiRascunhoNaoEditavelException.class,
                () -> service.atualizarRascunho(PDI_ID, ACAO_ID, novosDados()));

        verifyNoInteractions(acoesVersoes, acoes, eventos);
        assertSemMutacaoNemGravacao();
    }

    @ParameterizedTest
    @ValueSource(strings = {"AGUARDANDO_APROVACAO", "APROVADO", "REPROVADO", "RETIRADA",
            "SUBMETIDA_ANTERIORMENTE", "OUTRA_VERSAO_SUBMETIDA", "VERSAO_2",
            "VERSAO_VIGENTE", "PDI_EM_EXECUCAO", "PDI_CONCLUIDO"})
    void bloqueiaCadaCondicaoQueImpedeEdicaoDoRascunhoInicial(String condicao) {
        prepararVersao();
        switch (condicao) {
            case "AGUARDANDO_APROVACAO", "APROVADO", "REPROVADO", "RETIRADA" ->
                    versao.setStatusAprovacao(StatusAprovacaoPdi.valueOf(condicao));
            case "SUBMETIDA_ANTERIORMENTE" -> versao.setSubmetidaEm(Instant.parse("2026-01-02T12:00:00Z"));
            case "OUTRA_VERSAO_SUBMETIDA" -> when(versoes.contarVersoesSubmetidas(PDI_ID)).thenReturn(1L);
            case "VERSAO_2" -> versao.setNumero(2);
            case "VERSAO_VIGENTE" -> pdi.setVersaoVigente(versao);
            case "PDI_EM_EXECUCAO" -> pdi.setStatusExecucao(StatusExecucaoPdi.EM_EXECUCAO);
            case "PDI_CONCLUIDO" -> pdi.setStatusExecucao(StatusExecucaoPdi.CONCLUIDO);
            default -> throw new AssertionError("Condicao desconhecida");
        }
        var preservado = estadoProtegido();
        var anterior = dadosAtuais();

        assertThrows(PdiRascunhoNaoEditavelException.class,
                () -> service.atualizarRascunho(PDI_ID, ACAO_ID, novosDados()));

        assertEquals(preservado, estadoProtegido());
        assertEquals(anterior, dadosAtuais());
        verifyNoInteractions(acoesVersoes, acoes, eventos);
        assertSemMutacaoNemGravacao();
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void requisicaoNulaOuDatasInvertidasPreservaDadosSemSettersNemEscritas(boolean nula) {
        prepararVersao();
        var anterior = dadosAtuais();
        var preservado = estadoProtegido();
        var request = nula ? null : new AtualizarAcaoPdiRequest("Novo", null, FIM, INICIO, null);

        assertThrows(IllegalArgumentException.class,
                () -> service.atualizarRascunho(PDI_ID, ACAO_ID, request));

        assertEquals(anterior, dadosAtuais());
        assertEquals(preservado, estadoProtegido());
        verifyNoInteractions(acoesVersoes, acoes, eventos);
        assertSemMutacaoNemGravacao();
    }

    @Test
    void acaoAusenteNaVersaoAutorizadaNaoPodeSerEditada() {
        prepararVersao();
        when(acoesVersoes.buscarNaVersao(PDI_ID, VERSAO_ID, ACAO_ID)).thenReturn(Optional.empty());
        var anterior = dadosAtuais();

        assertThrows(PdiRascunhoNaoEncontradoException.class,
                () -> service.atualizarRascunho(PDI_ID, ACAO_ID, novosDados()));

        verify(acoesVersoes).buscarNaVersao(PDI_ID, VERSAO_ID, ACAO_ID);
        verifyNoMoreInteractions(acoesVersoes);
        assertEquals(anterior, dadosAtuais());
        assertSemMutacaoNemGravacao();
    }

    @ParameterizedTest
    @ValueSource(strings = {"CONTEUDO", "EVENTO"})
    void falhaDePersistenciaPropagaExcecaoESolicitaRollbackSemCommit(String etapa) {
        prepararConsultaConteudo();
        var falha = new DataIntegrityViolationException("Falha de persistencia simulada");
        if (etapa.equals("CONTEUDO")) {
            when(acoesVersoes.saveAndFlush(conteudo)).thenThrow(falha);
        } else {
            when(acoesVersoes.saveAndFlush(conteudo)).thenReturn(conteudo);
            when(eventos.saveAndFlush(any(PdiEvento.class))).thenThrow(falha);
        }
        PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
        TransactionStatus status = mock(TransactionStatus.class);
        when(manager.getTransaction(any(TransactionDefinition.class))).thenReturn(status);
        AcaoPdiService proxy = comIntercepcaoTransacional(manager);

        assertSame(falha, assertThrows(DataIntegrityViolationException.class,
                () -> proxy.atualizarRascunho(PDI_ID, ACAO_ID, novosDados())));

        var ordem = inOrder(manager, acoesVersoes, eventos);
        ordem.verify(manager).getTransaction(any(TransactionDefinition.class));
        ordem.verify(acoesVersoes).buscarNaVersao(PDI_ID, VERSAO_ID, ACAO_ID);
        ordem.verify(acoesVersoes).saveAndFlush(conteudo);
        if (etapa.equals("EVENTO")) {
            ordem.verify(eventos).saveAndFlush(any(PdiEvento.class));
        } else {
            verifyNoInteractions(eventos);
        }
        ordem.verify(manager).rollback(status);
        verify(manager, never()).commit(any());
        verifyNoInteractions(acoes);
        // This proves annotation-driven rollback coordination, not restored PostgreSQL rows.
        // A mock transaction manager cannot undo mutations in this detached Java fixture.
    }

    private AcaoPdiService comIntercepcaoTransacional(PlatformTransactionManager manager) {
        TransactionInterceptor interceptor = new TransactionInterceptor();
        interceptor.setTransactionManager(manager);
        interceptor.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
        ProxyFactory factory = new ProxyFactory(service);
        factory.setProxyTargetClass(true);
        factory.addAdvice(interceptor);
        return (AcaoPdiService) factory.getProxy();
    }

    private void prepararTitular() {
        when(autenticacao.getIfAvailable()).thenReturn(() -> TITULAR_ID);
        when(usuarios.findByIdForUpdate(TITULAR_ID)).thenReturn(Optional.of(titular));
    }

    private void prepararPdi() {
        prepararTitular();
        when(pdis.buscarDoTitularParaAtualizacao(PDI_ID, TITULAR_ID)).thenReturn(Optional.of(pdi));
    }

    private void prepararVersao() {
        prepararPdi();
        when(versoes.buscarInicialParaAtualizacao(PDI_ID)).thenReturn(Optional.of(versao));
    }

    private void prepararConsultaConteudo() {
        prepararVersao();
        when(acoesVersoes.buscarNaVersao(PDI_ID, VERSAO_ID, ACAO_ID)).thenReturn(Optional.of(conteudo));
    }

    private void prepararEdicao() {
        prepararConsultaConteudo();
        when(acoesVersoes.saveAndFlush(conteudo)).thenReturn(conteudo);
    }

    private void aplicarDadosNaFixture(AtualizarAcaoPdiRequest request) {
        conteudo.setTitulo(request.titulo());
        conteudo.setObjetivo(request.objetivo());
        conteudo.setInicioPlanejado(request.inicioPlanejado());
        conteudo.setFimPlanejado(request.fimPlanejado());
        conteudo.setResultadoEsperado(request.resultadoEsperado());
        clearInvocations(conteudo);
    }

    private void assertSemMutacaoNemGravacao() {
        verify(conteudo, never()).setTitulo(any());
        verify(conteudo, never()).setObjetivo(any());
        verify(conteudo, never()).setInicioPlanejado(any());
        verify(conteudo, never()).setFimPlanejado(any());
        verify(conteudo, never()).setResultadoEsperado(any());
        verify(conteudo, never()).setOrdem(any());
        verify(conteudo, never()).definirVinculos(any(), any());
        verify(acoesVersoes, never()).saveAndFlush(any());
        verify(versoes, never()).saveAndFlush(any());
        verifyNoInteractions(acoes, eventos);
    }

    private AtualizarAcaoPdiRequest dadosAtuais() {
        return new AtualizarAcaoPdiRequest(conteudo.getTitulo(), conteudo.getObjetivo(),
                conteudo.getInicioPlanejado(), conteudo.getFimPlanejado(), conteudo.getResultadoEsperado());
    }

    private static AtualizarAcaoPdiRequest novosDados() {
        return new AtualizarAcaoPdiRequest("Titulo novo", "Objetivo novo", INICIO.plusDays(2),
                FIM.plusDays(3), "Resultado novo");
    }

    private AcaoPdiResponse responseEsperada(AtualizarAcaoPdiRequest request) {
        return new AcaoPdiResponse(ACAO_ID, CONTEUDO_ID, PDI_ID, VERSAO_ID, request.titulo(),
                request.objetivo(), request.resultadoEsperado(), request.inicioPlanejado(),
                request.fimPlanejado(), 9, acao.getStatusExecucao());
    }

    private Map<String, Map<String, Object>> estadoProtegido() {
        return Map.of(
                "titular", campos(titular, Usuario.class, Set.of()),
                "pdi", campos(pdi, PDI.class, Set.of()),
                "versao", campos(versao, PdiVersao.class, Set.of()),
                "acao", campos(acao, AcaoPDI.class, Set.of()),
                "conteudo", campos(conteudo, AcaoPdiVersao.class, CAMPOS_EDITAVEIS));
    }

    /** Capture every stored field so future noneditable properties are protected as well. */
    private static Map<String, Object> campos(Object entidade, Class<?> tipo, Set<String> ignorados) {
        Map<String, Object> valores = new LinkedHashMap<>();
        for (var campo : tipo.getDeclaredFields()) {
            if (!Modifier.isStatic(campo.getModifiers()) && !ignorados.contains(campo.getName())) {
                valores.put(campo.getName(), ReflectionTestUtils.getField(entidade, campo.getName()));
            }
        }
        return valores;
    }
}
