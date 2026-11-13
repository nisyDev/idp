package com.empresa.pdi.controller;

import com.empresa.pdi.enums.PerfilUsuario;
import com.empresa.pdi.enums.StatusAprovacaoPdi;
import com.empresa.pdi.enums.StatusExecucaoAcao;
import com.empresa.pdi.enums.StatusExecucaoPdi;
import com.empresa.pdi.model.AcaoPDI;
import com.empresa.pdi.model.AcaoPdiVersao;
import com.empresa.pdi.model.PDI;
import com.empresa.pdi.model.PdiEvento;
import com.empresa.pdi.model.PdiVersao;
import com.empresa.pdi.model.Usuario;
import com.empresa.pdi.model.UsuarioCredencialLocal;
import com.empresa.pdi.repository.AcaoPDIRepository;
import com.empresa.pdi.repository.AcaoPdiVersaoRepository;
import com.empresa.pdi.repository.PDIRepository;
import com.empresa.pdi.repository.PdiEventoRepository;
import com.empresa.pdi.repository.PdiVersaoRepository;
import com.empresa.pdi.repository.UsuarioCredencialLocalRepository;
import com.empresa.pdi.repository.UsuarioRepository;
import com.empresa.pdi.security.HttpSecurityConfig;
import com.empresa.pdi.security.LocalAuthenticationConfig;
import com.empresa.pdi.security.LocalHttpSecurityConfig;
import com.empresa.pdi.security.SecurityContextUsuarioAutenticadoProvider;
import com.empresa.pdi.security.UsuarioPrincipal;
import com.empresa.pdi.service.AcaoPdiService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManagerFactory;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Real session login, CSRF filters and action service; repositories are mocks, never JDBC.
 * Persistence failures prove HTTP failure and call coordination, not database rollback.
 */
@WebMvcTest(controllers = {AcaoPDIController.class, CsrfController.class}, excludeAutoConfiguration = {
        DataSourceAutoConfiguration.class, HibernateJpaAutoConfiguration.class, FlywayAutoConfiguration.class})
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@Import({HttpSecurityConfig.class, LocalAuthenticationConfig.class, LocalHttpSecurityConfig.class,
        SecurityContextUsuarioAutenticadoProvider.class, AcaoPdiService.class, PdiExceptionHandler.class})
@ActiveProfiles(value = "auth-local", inheritProfiles = false)
class AcaoPdiEdicaoHttpTest {
    private static final UUID OWNER = UUID.randomUUID();
    private static final UUID OTHER = UUID.randomUUID();
    private static final String EMAIL = "titular-acao@talentsynk.invalid";
    // Synthetic, ephemeral credentials are used only by the real test login filter.
    private static final String PASSWORD = UUID.randomUUID().toString();
    private static final String HASH = new BCryptPasswordEncoder(12).encode(PASSWORD);
    private static final String PATH = "/api/pdis/11/acoes/31";
    private static final String CHANGE = """
            {"titulo":"Título atualizado","objetivo":"Objetivo atualizado",
             "inicioPlanejado":"2026-09-01","fimPlanejado":"2026-09-30",
             "resultadoEsperado":"Resultado atualizado"}
            """;

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired ApplicationContext context;
    @MockBean UsuarioRepository usuarios;
    @MockBean UsuarioCredencialLocalRepository credenciais;
    @MockBean PDIRepository pdis;
    @MockBean PdiVersaoRepository versoes;
    @MockBean AcaoPDIRepository acoes;
    @MockBean AcaoPdiVersaoRepository acoesVersoes;
    @MockBean PdiEventoRepository eventos;

    private Usuario owner;
    private PdiVersao draft;
    private AcaoPdiVersao action;

    @BeforeEach
    void fixturesRemainIsolatedFromPersistence() {
        assertTrue(context.getBeansOfType(DataSource.class).isEmpty());
        assertTrue(context.getBeansOfType(EntityManagerFactory.class).isEmpty());
        assertTrue(context.getBeansOfType(Flyway.class).isEmpty());
        assertTrue(context.containsBean("localSecurity"));
        assertFalse(context.containsBean("demoSecurity"));
        assertFalse(context.containsBean("closedSecurity"));
        owner = user(OWNER);
        Usuario other = user(OTHER);
        owner.setEmail(EMAIL);
        UsuarioCredencialLocal credential = new UsuarioCredencialLocal(owner, HASH);
        when(usuarios.findByEmailIgnoreCase(EMAIL)).thenReturn(Optional.of(owner));
        when(credenciais.findById(OWNER)).thenReturn(Optional.of(credential));
        when(usuarios.findById(OWNER)).thenReturn(Optional.of(owner));
        when(usuarios.findByIdForUpdate(OWNER)).thenReturn(Optional.of(owner));
        draft = draft(owner, 11L, 21L);
        action = action(draft, 31L, 41L);
        PdiVersao foreignDraft = draft(other, 12L, 22L);
        PdiVersao otherOwnDraft = draft(owner, 13L, 23L);
        PdiVersao differentVersion = draft(owner, 11L, 24L);
        differentVersion.setNumero(2);
        List<AcaoPdiVersao> allActions = List.of(action,
                action(foreignDraft, 32L, 42L), action(otherOwnDraft, 33L, 43L),
                action(differentVersion, 34L, 44L));
        when(pdis.buscarDoTitularParaAtualizacao(anyLong(), any(UUID.class)))
                .thenAnswer(call -> Long.valueOf(11).equals(call.getArgument(0))
                        && OWNER.equals(call.getArgument(1)) ? Optional.of(draft.getPdi()) : Optional.empty());
        when(versoes.buscarInicialParaAtualizacao(11L)).thenReturn(Optional.of(draft));
        when(acoesVersoes.buscarNaVersao(anyLong(), anyLong(), anyLong())).thenAnswer(call ->
                allActions.stream().filter(value -> value.getPdiId().equals(call.getArgument(0))
                        && value.getPdiVersaoId().equals(call.getArgument(1))
                        && value.getAcaoPdiId().equals(call.getArgument(2))).findFirst());
        when(acoesVersoes.saveAndFlush(any(AcaoPdiVersao.class))).thenAnswer(call -> call.getArgument(0));
    }

    @ParameterizedTest
    @EnumSource(PerfilUsuario.class)
    void eachInternalProfileCanEditItsOwnDraftWithDtoAndNoStore(PerfilUsuario profile) throws Exception {
        owner.setPerfilAcesso(profile);
        Token token = authenticatedToken();
        SecurityContext security = assertInstanceOf(SecurityContext.class,
                token.session().getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY));
        UsuarioPrincipal principal = assertInstanceOf(UsuarioPrincipal.class, security.getAuthentication().getPrincipal());
        assertEquals(profile, principal.getPerfilAcesso());
        MvcResult result = edit(token, PATH, CHANGE).andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(header().doesNotExist(HttpHeaders.LOCATION))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.titulo").value("Título atualizado"))
                .andExpect(jsonPath("$.objetivo").value("Objetivo atualizado"))
                .andExpect(jsonPath("$.inicioPlanejado").value("2026-09-01"))
                .andExpect(jsonPath("$.fimPlanejado").value("2026-09-30"))
                .andExpect(jsonPath("$.resultadoEsperado").value("Resultado atualizado")).andReturn();
        assertDtoAndIdentity(result);
        ArgumentCaptor<PdiEvento> event = ArgumentCaptor.forClass(PdiEvento.class);
        verify(eventos, times(1)).saveAndFlush(event.capture());
        assertEquals("ACAO_EDITADA", event.getValue().getTipo());
        assertSame(owner, event.getValue().getAutor());
        assertSame(draft, event.getValue().getPdiVersao());
        assertSame(action.getAcaoPdi(), event.getValue().getAcaoPdi());
        assertSame(action, event.getValue().getAcaoPdiVersao());
        assertEquals(Set.of("titulo", "objetivo", "inicioPlanejado", "fimPlanejado", "resultadoEsperado"),
                fieldNames(event.getValue().getSnapshot()));
        assertEquals("Título anterior", event.getValue().getSnapshot().path("titulo").asText());
        assertEquals("2026-08-01", event.getValue().getSnapshot().path("inicioPlanejado").asText());
        assertEquals("Título atualizado", event.getValue().getDados().path("titulo").asText());
        verify(usuarios, times(1)).findByEmailIgnoreCase(EMAIL);
        verify(acoesVersoes).buscarNaVersao(11L, 21L, 31L);
        verify(acoesVersoes).saveAndFlush(action);
        verifyNoInteractions(acoes);
        verify(pdis, never()).saveAndFlush(any());
        verify(versoes, never()).saveAndFlush(any());
    }

    @Test
    void putReplacesAllFieldsWithExplicitNulls() throws Exception {
        MvcResult result = edit(authenticatedToken(), PATH, """
                {"titulo":null,"objetivo":null,"inicioPlanejado":null,
                 "fimPlanejado":null,"resultadoEsperado":null}
                """).andExpect(status().isOk()).andReturn();
        JsonNode json = mapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
        for (String name : List.of("titulo", "objetivo", "inicioPlanejado", "fimPlanejado", "resultadoEsperado")) {
            assertTrue(json.has(name));
            assertTrue(json.get(name).isNull());
        }
        assertDtoAndIdentity(result);
        ArgumentCaptor<PdiEvento> event = ArgumentCaptor.forClass(PdiEvento.class);
        verify(eventos).saveAndFlush(event.capture());
        assertEquals(5, event.getValue().getDados().size());
        event.getValue().getDados().forEach(value -> assertTrue(value.isNull()));
    }

    @Test
    void unchangedPutReturnsCurrentDtoWithoutWritesOrNewEvent() throws Exception {
        MvcResult result = edit(authenticatedToken(), PATH, """
                {"titulo":"Título anterior","objetivo":"Objetivo anterior",
                 "inicioPlanejado":"2026-08-01","fimPlanejado":"2026-08-31",
                 "resultadoEsperado":"Resultado anterior"}
                """).andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store")).andReturn();
        assertDtoAndIdentity(result);
        assertNoWrites();
    }

    @ParameterizedTest
    @ValueSource(strings = {"{", "[]", "null", "{\"inicioPlanejado\":\"data-invalida\"}",
            "{\"inicioPlanejado\":\"2026-02-30\"}", "{\"inicioPlanejado\":{}}",
            "{\"inicioPlanejado\":\"2026-09-30\",\"fimPlanejado\":\"2026-09-01\"}"})
    void invalidJsonDatesAndIntervalsAre400WithoutChangingContent(String body) throws Exception {
        edit(authenticatedToken(), PATH, body).andExpect(status().isBadRequest());
        assertOriginalContent();
        assertNoWrites();
    }

    @Test
    void absentSessionWithValidAnonymousCsrfIs401() throws Exception {
        Token anonymous = token(null);
        edit(anonymous, PATH, CHANGE).andExpect(status().isUnauthorized())
                .andExpect(header().doesNotExist(HttpHeaders.LOCATION));
        verifyNoInteractions(usuarios, credenciais, pdis, versoes, acoes, acoesVersoes, eventos);
    }

    @Test
    void expiredSessionCannotAuthorizeASubsequentRequest() throws Exception {
        Token loggedIn = authenticatedToken();
        loggedIn.session().invalidate();
        assertTrue(loggedIn.session().isInvalid());
        // MockMvc models a request after expiry; an invalid MockHttpSession must not be reused.
        Token anonymous = token(null);
        edit(anonymous, PATH, CHANGE).andExpect(status().isUnauthorized());
        assertOriginalContent();
        assertNoWrites();
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void missingOrInvalidCsrfIs403BeforeService(boolean missing) throws Exception {
        Token token = authenticatedToken();
        MockHttpServletRequestBuilder request = put(PATH).session(token.session())
                .contentType(MediaType.APPLICATION_JSON).content(CHANGE);
        if (!missing) request.header(token.header(), "csrf-invalido");
        mvc.perform(request).andExpect(status().isForbidden());
        verifyNoInteractions(pdis, versoes, acoes, acoesVersoes, eventos);
        assertOriginalContent();
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void inactiveOrRemovedUserAfterLoginIs401(boolean inactive) throws Exception {
        Token token = authenticatedToken();
        if (inactive) owner.setAtivo(false);
        else when(usuarios.findById(OWNER)).thenReturn(Optional.empty());
        edit(token, PATH, CHANGE).andExpect(status().isUnauthorized());
        verifyNoInteractions(pdis, versoes, acoes, acoesVersoes, eventos);
        assertOriginalContent();
    }

    @ParameterizedTest
    @EnumSource(PerfilUsuario.class)
    void missingAndForeignPdiHaveTheSame404ForEveryProfile(PerfilUsuario profile) throws Exception {
        owner.setPerfilAcesso(profile);
        Token token = authenticatedToken();
        MvcResult missing = edit(token, "/api/pdis/999/acoes/31", CHANGE)
                .andExpect(status().isNotFound()).andReturn();
        MvcResult foreign = edit(token, "/api/pdis/12/acoes/32", CHANGE)
                .andExpect(status().isNotFound()).andReturn();
        JsonNode first = mapper.readTree(missing.getResponse().getContentAsString());
        JsonNode second = mapper.readTree(foreign.getResponse().getContentAsString());
        assertEquals(first.path("detail"), second.path("detail"));
        verify(pdis).buscarDoTitularParaAtualizacao(999L, OWNER);
        verify(pdis).buscarDoTitularParaAtualizacao(12L, OWNER);
        verifyNoInteractions(acoesVersoes);
        assertOriginalContent();
        assertNoWrites();
    }

    @ParameterizedTest
    @ValueSource(longs = {999, 32, 33, 34})
    void missingActionOrActionOutsideSelectedPdiAndVersionIs404(long actionId) throws Exception {
        edit(authenticatedToken(), "/api/pdis/11/acoes/" + actionId, CHANGE)
                .andExpect(status().isNotFound());
        verify(acoesVersoes).buscarNaVersao(11L, 21L, actionId);
        assertOriginalContent();
        assertNoWrites();
    }

    @ParameterizedTest
    @ValueSource(strings = {"SUBMITTED", "APPROVED", "NO_INITIAL_VERSION", "CURRENT_VERSION",
            "EXECUTING", "PREVIOUS_SUBMISSION"})
    void ineligiblePdiOrVersionIs409WithoutWrites(String state) throws Exception {
        switch (state) {
            case "SUBMITTED" -> {
                draft.setStatusAprovacao(StatusAprovacaoPdi.AGUARDANDO_APROVACAO);
                draft.setSubmetidaEm(Instant.parse("2026-08-01T00:00:00Z"));
            }
            case "APPROVED" -> draft.setStatusAprovacao(StatusAprovacaoPdi.APROVADO);
            case "NO_INITIAL_VERSION" -> when(versoes.buscarInicialParaAtualizacao(11L)).thenReturn(Optional.empty());
            case "CURRENT_VERSION" -> draft.getPdi().setVersaoVigente(draft);
            case "EXECUTING" -> draft.getPdi().setStatusExecucao(StatusExecucaoPdi.EM_EXECUCAO);
            case "PREVIOUS_SUBMISSION" -> when(versoes.contarVersoesSubmetidas(11L)).thenReturn(1L);
            default -> throw new IllegalArgumentException("Cenário de teste desconhecido");
        }
        edit(authenticatedToken(), PATH, CHANGE).andExpect(status().isConflict());
        verifyNoInteractions(acoesVersoes);
        assertOriginalContent();
        assertNoWrites();
    }

    @Test
    void lockFailureIs409WithoutPersistence() throws Exception {
        when(pdis.buscarDoTitularParaAtualizacao(11L, OWNER))
                .thenThrow(new CannotAcquireLockException("Conflito sintético de teste"));
        edit(authenticatedToken(), PATH, CHANGE).andExpect(status().isConflict());
        assertOriginalContent();
        assertNoWrites();
    }

    @Test
    void payloadCannotControlIdentityOwnershipVersionOrderOrStates() throws Exception {
        String body = """
                {"titulo":"Conteúdo permitido","objetivo":null,"inicioPlanejado":null,
                 "fimPlanejado":null,"resultadoEsperado":null,
                 "id":999,"acaoVersaoId":999,"pdiId":999,"pdiVersaoId":999,
                 "titularId":"%s","usuarioId":"%s","autorId":"%s",
                 "ordem":999,"numeroVersao":999,"statusExecucao":"CONCLUIDA",
                 "statusAprovacao":"APROVADO","progresso":100}
                """.formatted(OTHER, OTHER, OTHER);
        MvcResult result = edit(authenticatedToken(), PATH, body).andExpect(status().isOk()).andReturn();
        assertDtoAndIdentity(result);
        assertEquals("Conteúdo permitido", action.getTitulo());
        assertSame(owner, draft.getPdi().getUsuario());
        ArgumentCaptor<PdiEvento> event = ArgumentCaptor.forClass(PdiEvento.class);
        verify(eventos).saveAndFlush(event.capture());
        assertSame(owner, event.getValue().getAutor());
        assertEquals(5, event.getValue().getDados().size());
    }

    @Test
    void eventPersistenceFailureReturnsSanitized500InsteadOfSuccess() throws Exception {
        String internalDetail = "Falha sintética de persistência do evento";
        when(eventos.saveAndFlush(any(PdiEvento.class)))
                .thenThrow(new DataIntegrityViolationException(internalDetail));
        MvcResult result = edit(authenticatedToken(), PATH, CHANGE)
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.detail").value("Não foi possível processar o rascunho"))
                .andExpect(header().doesNotExist(HttpHeaders.LOCATION)).andReturn();
        assertFalse(result.getResponse().getContentAsString(StandardCharsets.UTF_8).contains(internalDetail));
        var calls = inOrder(acoesVersoes, eventos);
        calls.verify(acoesVersoes).saveAndFlush(action);
        calls.verify(eventos).saveAndFlush(any(PdiEvento.class));
        verifyNoInteractions(acoes);
        // Mocked repositories cannot roll back this in-memory fixture; real rollback is a separate IT.
    }

    @Test
    void contentPersistenceFailureDoesNotAppendEventOrReturnSuccess() throws Exception {
        when(acoesVersoes.saveAndFlush(any(AcaoPdiVersao.class)))
                .thenThrow(new DataIntegrityViolationException("Falha sintética do conteúdo"));
        edit(authenticatedToken(), PATH, CHANGE).andExpect(status().isInternalServerError());
        verifyNoInteractions(eventos, acoes);
    }

    @Test
    void deletePatchAndLegacyRoutesRemainForbiddenEvenWithCsrf() throws Exception {
        Token token = authenticatedToken();
        for (MockHttpServletRequestBuilder request : List.of(delete(PATH), patch(PATH),
                put("/api/acoes/31"), post("/api/pdis/11/acoes/31/componentes"))) {
            mvc.perform(request.session(token.session()).header(token.header(), token.value())
                            .contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isForbidden());
        }
        assertOriginalContent();
        assertNoWrites();
    }

    private ResultActions edit(Token token, String path, String body) throws Exception {
        return mvc.perform(put(path).session(token.session()).header(token.header(), token.value())
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private Token authenticatedToken() throws Exception {
        Token before = token(null);
        MvcResult result = mvc.perform(post("/api/auth/login").session(before.session())
                        .header(before.header(), before.value())
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("email", EMAIL).param("password", PASSWORD))
                .andExpect(status().isNoContent()).andReturn();
        MockHttpSession session = assertInstanceOf(MockHttpSession.class, result.getRequest().getSession(false));
        return token(session); // Login rotates the stored CSRF token; always obtain the new one.
    }

    private Token token(MockHttpSession existing) throws Exception {
        MockHttpServletRequestBuilder request = get("/api/csrf");
        if (existing != null) request.session(existing);
        MvcResult result = mvc.perform(request).andExpect(status().isOk()).andReturn();
        JsonNode json = mapper.readTree(result.getResponse().getContentAsString());
        return new Token(assertInstanceOf(MockHttpSession.class, result.getRequest().getSession(false)),
                json.path("headerName").asText(), json.path("token").asText());
    }

    private void assertDtoAndIdentity(MvcResult result) throws Exception {
        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        JsonNode json = mapper.readTree(body);
        assertEquals(Set.of("id", "acaoVersaoId", "pdiId", "pdiVersaoId", "titulo", "objetivo",
                "inicioPlanejado", "fimPlanejado", "resultadoEsperado", "ordem", "statusExecucao"), fieldNames(json));
        json.forEach(value -> assertFalse(value.isObject() || value.isArray()));
        assertEquals(31L, json.path("id").asLong());
        assertEquals(41L, json.path("acaoVersaoId").asLong());
        assertEquals(11L, json.path("pdiId").asLong());
        assertEquals(21L, json.path("pdiVersaoId").asLong());
        assertEquals(7, json.path("ordem").asInt());
        assertEquals(StatusExecucaoAcao.NAO_INICIADA.name(), json.path("statusExecucao").asText());
        assertEquals(OWNER, draft.getPdi().getUsuario().getId());
        assertEquals(1, draft.getNumero());
        assertEquals(StatusAprovacaoPdi.RASCUNHO, draft.getStatusAprovacao());
        assertEquals(StatusExecucaoPdi.NAO_INICIADO, draft.getPdi().getStatusExecucao());
        assertNull(draft.getPdi().getVersaoVigenteId());
        assertFalse(body.contains(PASSWORD), "A resposta não pode conter senha");
        assertFalse(body.contains(HASH), "A resposta não pode conter hash");
    }

    private void assertOriginalContent() {
        assertEquals("Título anterior", action.getTitulo());
        assertEquals("Objetivo anterior", action.getObjetivo());
        assertEquals("Resultado anterior", action.getResultadoEsperado());
        assertEquals(LocalDate.of(2026, 8, 1), action.getInicioPlanejado());
        assertEquals(LocalDate.of(2026, 8, 31), action.getFimPlanejado());
        assertEquals(7, action.getOrdem());
    }

    private void assertNoWrites() {
        verify(acoesVersoes, never()).saveAndFlush(any());
        verifyNoInteractions(eventos, acoes);
        verify(pdis, never()).saveAndFlush(any());
        verify(versoes, never()).saveAndFlush(any());
        verify(usuarios, never()).save(any());
        verify(credenciais, never()).save(any());
    }

    private static Set<String> fieldNames(JsonNode node) {
        Set<String> names = new HashSet<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private static Usuario user(UUID id) {
        Usuario user = new Usuario();
        ReflectionTestUtils.setField(user, "id", id);
        user.setNome("Pessoa fictícia de teste");
        user.setPerfilAcesso(PerfilUsuario.COLABORADOR);
        user.setAtivo(true);
        return user;
    }

    private static PdiVersao draft(Usuario user, Long id, Long versionId) {
        PDI pdi = new PDI();
        ReflectionTestUtils.setField(pdi, "id", id);
        pdi.setUsuario(user);
        PdiVersao version = new PdiVersao();
        ReflectionTestUtils.setField(version, "id", versionId);
        version.setPdi(pdi);
        version.setNumero(1);
        version.setStatusAprovacao(StatusAprovacaoPdi.RASCUNHO);
        return version;
    }

    private static AcaoPdiVersao action(PdiVersao version, Long actionId, Long contentId) {
        AcaoPDI identity = new AcaoPDI();
        ReflectionTestUtils.setField(identity, "id", actionId);
        identity.setPdi(version.getPdi());
        AcaoPdiVersao content = new AcaoPdiVersao();
        ReflectionTestUtils.setField(content, "id", contentId);
        content.definirVinculos(version, identity);
        content.setTitulo("Título anterior");
        content.setObjetivo("Objetivo anterior");
        content.setResultadoEsperado("Resultado anterior");
        content.setInicioPlanejado(LocalDate.of(2026, 8, 1));
        content.setFimPlanejado(LocalDate.of(2026, 8, 31));
        content.setOrdem(7);
        return content;
    }

    private record Token(MockHttpSession session, String header, String value) {
        @Override public String toString() { return "Token [redacted]"; }
    }
}
