package com.empresa.pdi.controller;

import com.empresa.pdi.enums.StatusAprovacaoPdi;
import com.empresa.pdi.model.PDI;
import com.empresa.pdi.model.AcaoPDI;
import com.empresa.pdi.model.AcaoPdiVersao;
import com.empresa.pdi.model.PdiEvento;
import com.empresa.pdi.model.PdiVersao;
import com.empresa.pdi.model.Usuario;
import com.empresa.pdi.repository.PDIRepository;
import com.empresa.pdi.repository.AcaoPDIRepository;
import com.empresa.pdi.repository.AcaoPdiVersaoRepository;
import com.empresa.pdi.repository.PdiEventoRepository;
import com.empresa.pdi.repository.PdiVersaoRepository;
import com.empresa.pdi.repository.UsuarioRepository;
import com.empresa.pdi.security.DemoAuthenticationConfig;
import com.empresa.pdi.security.HttpSecurityConfig;
import com.empresa.pdi.security.SecurityContextUsuarioAutenticadoProvider;
import com.empresa.pdi.service.PdiService;
import com.empresa.pdi.service.PdiAcessoService;
import jakarta.persistence.EntityManager;
import com.empresa.pdi.service.AcaoPdiService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** MVC slice with real Basic authentication, CSRF, identity adapter and PDI service.
 * Only repositories are mocked; no datasource, JPA bootstrap or network is needed.
 */
@WebMvcTest(controllers = {PDIController.class, AcaoPDIController.class, CsrfController.class})
@Import({HttpSecurityConfig.class, DemoAuthenticationConfig.class,
        SecurityContextUsuarioAutenticadoProvider.class, PdiService.class, PdiAcessoService.class, AcaoPdiService.class, PdiExceptionHandler.class})
@ActiveProfiles("demo-local")
class PdiHttpTest {
    private static final UUID OWNER = UUID.randomUUID();
    private static final UUID OTHER = UUID.randomUUID();
    // Ephemeral synthetic test credentials, never loaded from external accounts.
    private static final String PASSWORD = UUID.randomUUID().toString();

    @DynamicPropertySource
    static void accounts(DynamicPropertyRegistry registry) throws Exception {
        var encoder = new BCryptPasswordEncoder(10);
        String json = new ObjectMapper().writeValueAsString(List.of(
                Map.of("login", "owner", "passwordHash", encoder.encode(PASSWORD), "usuarioId", OWNER.toString()),
                Map.of("login", "other", "passwordHash", encoder.encode(PASSWORD), "usuarioId", OTHER.toString())));
        registry.add("TALENTSYNK_DEMO_ACCOUNTS", () -> json);
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @MockBean EntityManager entityManager;
    @MockBean UsuarioRepository usuarios;
    @MockBean PDIRepository pdis;
    @MockBean PdiVersaoRepository versoes;
    @MockBean PdiEventoRepository eventos;
    @MockBean AcaoPDIRepository acoes;
    @MockBean AcaoPdiVersaoRepository acoesVersoes;

    private Usuario owner;
    private PdiVersao draft;
    private AcaoPdiVersao draftAction;

    @BeforeEach
    void fixtures() {
        owner = usuario(OWNER);
        Usuario other = usuario(OTHER);
        when(usuarios.findById(OWNER)).thenReturn(Optional.of(owner));
        when(usuarios.findById(OTHER)).thenReturn(Optional.of(other));
        when(usuarios.findByIdForUpdate(OWNER)).thenReturn(Optional.of(owner));
        when(usuarios.findByIdForUpdate(OTHER)).thenReturn(Optional.of(other));
        draft = draft(owner, 11L, 21L);
        PdiVersao otherDraft = draft(other, 12L, 23L);
        PdiVersao anotherVersion = draft(owner, 11L, 22L);
        anotherVersion.setNumero(2);
        anotherVersion.setStatusAprovacao(StatusAprovacaoPdi.APROVADO);
        draftAction = action(draft, 31L, 41L, "Acao privada");
        List<AcaoPdiVersao> allActions = List.of(draftAction,
                action(anotherVersion, 32L, 42L, "Outra versao"),
                action(otherDraft, 33L, 43L, "Outro titular"));
        when(acoesVersoes.listarNaVersao(anyLong(), anyLong())).thenAnswer(call -> allActions.stream()
                .filter(value -> value.getPdiId().equals(call.getArgument(0))
                        && value.getPdiVersaoId().equals(call.getArgument(1))).toList());
        when(acoesVersoes.buscarNaVersao(anyLong(), anyLong(), anyLong())).thenAnswer(call -> allActions.stream()
                .filter(value -> value.getPdiId().equals(call.getArgument(0))
                        && value.getPdiVersaoId().equals(call.getArgument(1))
                        && value.getAcaoPdiId().equals(call.getArgument(2))).findFirst());
        when(acoes.saveAndFlush(any(AcaoPDI.class))).thenAnswer(call -> {
            AcaoPDI value = call.getArgument(0);
            ReflectionTestUtils.setField(value, "id", 31L);
            return value;
        });
        when(acoesVersoes.saveAndFlush(any(AcaoPdiVersao.class))).thenAnswer(call -> {
            AcaoPdiVersao value = call.getArgument(0);
            ReflectionTestUtils.setField(value, "id", 41L);
            return value;
        });
        when(pdis.buscarDoTitularParaAtualizacao(anyLong(), any(UUID.class)))
                .thenAnswer(call -> Long.valueOf(11).equals(call.getArgument(0))
                        && OWNER.equals(call.getArgument(1)) ? Optional.of(draft.getPdi()) : Optional.empty());
        when(versoes.buscarEditavelParaAtualizacao(11L)).thenReturn(Optional.of(draft));
        when(versoes.buscarRascunho(anyLong(), any(UUID.class), eq(StatusAprovacaoPdi.RASCUNHO)))
                .thenAnswer(call -> Long.valueOf(11).equals(call.getArgument(0))
                        && OWNER.equals(call.getArgument(1)) ? Optional.of(draft)
                        : Long.valueOf(12).equals(call.getArgument(0)) && OTHER.equals(call.getArgument(1))
                        ? Optional.of(otherDraft) : Optional.empty());
        when(versoes.listarRascunhos(any(UUID.class), eq(StatusAprovacaoPdi.RASCUNHO)))
                .thenAnswer(call -> OWNER.equals(call.getArgument(0)) ? List.of(draft) : List.of());
        when(pdis.saveAndFlush(any(PDI.class))).thenAnswer(call -> {
            PDI value = call.getArgument(0);
            ReflectionTestUtils.setField(value, "id", 11L);
            ReflectionTestUtils.setField(value, "criadoEm", Instant.parse("2026-01-01T00:00:00Z"));
            return value;
        });
        when(versoes.saveAndFlush(any(PdiVersao.class))).thenAnswer(call -> {
            PdiVersao value = call.getArgument(0);
            ReflectionTestUtils.setField(value, "id", 21L);
            ReflectionTestUtils.setField(value, "criadaEm", Instant.parse("2026-01-01T00:00:00Z"));
            return value;
        });
    }

    @Test
    void anonymousRequestsCannotReachPdi() throws Exception {
        mvc.perform(get("/api/pdis")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/pdis/11")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/csrf")).andExpect(status().isUnauthorized());
        // Default CSRF processing rejects unsafe requests before HTTP authorization.
        mvc.perform(post("/api/pdis").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(pdis, versoes, eventos);
    }

    @Test
    void realBasicAndSessionCsrfCreatePrivateDraft() throws Exception {
        Token token = token("owner");
        MvcResult result = mvc.perform(post("/api/pdis").header(HttpHeaders.AUTHORIZATION, basic("owner"))
                        .session(token.session()).header(token.header(), token.value())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"titulo\":\"Meu rascunho\",\"objetivo\":\"Aprender\"}"))
                .andExpect(status().isCreated()).andExpect(header().string("Location", "/api/pdis/11"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.statusAprovacao").value("RASCUNHO"))
                .andExpect(jsonPath("$.statusExecucao").value("NAO_INICIADO"))
                .andExpect(jsonPath("$.numeroVersao").value(1))
                .andExpect(jsonPath("$.titulo").value("Meu rascunho")).andReturn();
        assertDto(mapper.readTree(result.getResponse().getContentAsString()));
        var plan = ArgumentCaptor.forClass(PDI.class);
        verify(pdis).saveAndFlush(plan.capture());
        assertSame(owner, plan.getValue().getUsuario());
        assertNull(plan.getValue().getVersaoVigenteId());
        var event = ArgumentCaptor.forClass(PdiEvento.class);
        verify(eventos).saveAndFlush(event.capture());
        assertEquals(OWNER, event.getValue().getAutor().getId());
        assertEquals(21L, event.getValue().getPdiVersaoId());
    }

    @Test
    void authenticatedPostWithoutOrWithInvalidCsrfIsForbidden() throws Exception {
        mvc.perform(post("/api/pdis").header(HttpHeaders.AUTHORIZATION, basic("owner"))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
        Token token = token("owner");
        mvc.perform(post("/api/pdis").header(HttpHeaders.AUTHORIZATION, basic("owner"))
                        .session(token.session()).header(token.header(), "invalid")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(pdis, versoes, eventos);
    }

    @Test
    void ownerReadsDraftAsDto() throws Exception {
        var result = mvc.perform(get("/api/pdis/11").header(HttpHeaders.AUTHORIZATION, basic("owner")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(11))
                .andExpect(jsonPath("$.versaoId").value(21)).andReturn();
        assertDto(mapper.readTree(result.getResponse().getContentAsString()));
        verify(versoes).buscarRascunho(11L, OWNER, StatusAprovacaoPdi.RASCUNHO);
    }

    @Test
    void missingAndOtherOwnersDraftHaveSame404Contract() throws Exception {
        var missing = mvc.perform(get("/api/pdis/999").header(HttpHeaders.AUTHORIZATION, basic("owner")))
                .andExpect(status().isNotFound()).andReturn();
        var forbidden = mvc.perform(get("/api/pdis/11").header(HttpHeaders.AUTHORIZATION, basic("other")))
                .andExpect(status().isNotFound()).andReturn();
        JsonNode first = mapper.readTree(missing.getResponse().getContentAsString());
        JsonNode second = mapper.readTree(forbidden.getResponse().getContentAsString());
        assertEquals(first.get("detail"), second.get("detail"));
        assertEquals(first.get("title"), second.get("title"));
        verify(versoes).buscarRascunho(11L, OTHER, StatusAprovacaoPdi.RASCUNHO);
    }

    @Test
    void listUsesAuthenticatedOwnerScope() throws Exception {
        var result = mvc.perform(get("/api/pdis").header(HttpHeaders.AUTHORIZATION, basic("owner")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1)).andReturn();
        assertDto(mapper.readTree(result.getResponse().getContentAsString()).get(0));
        mvc.perform(get("/api/pdis").header(HttpHeaders.AUTHORIZATION, basic("other")))
                .andExpect(status().isOk()).andExpect(content().json("[]"));
        verify(versoes).listarRascunhos(OWNER, StatusAprovacaoPdi.RASCUNHO);
        verify(versoes).listarRascunhos(OTHER, StatusAprovacaoPdi.RASCUNHO);
        verifyNoInteractions(pdis, eventos);
    }

    @Test
    void legacyAndUnreleasedRoutesStayForbidden() throws Exception {
        for (String path : List.of("/api/pdis/usuario/" + OWNER, "/api/usuarios", "/api/acoes", "/api/reunioes")) {
            mvc.perform(get(path).header(HttpHeaders.AUTHORIZATION, basic("owner")))
                    .andExpect(status().isForbidden());
        }
        verifyNoInteractions(pdis, versoes, eventos);
    }

    @Test
    void invalidDatesAre400AndDoNotWrite() throws Exception {
        Token token = token("owner");
        mvc.perform(post("/api/pdis").header(HttpHeaders.AUTHORIZATION, basic("owner"))
                        .session(token.session()).header(token.header(), token.value())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"inicioPlanejado\":\"2026-12-31\",\"fimPlanejado\":\"2026-01-01\"}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(pdis, versoes, eventos);
    }

    @Test
    void invalidCredentialsAndInactiveUserCannotAuthenticate() throws Exception {
        mvc.perform(get("/api/pdis").header(HttpHeaders.AUTHORIZATION,
                        "Basic " + Base64.getEncoder().encodeToString("owner:wrong".getBytes(StandardCharsets.UTF_8))))
                .andExpect(status().isUnauthorized());
        owner.setAtivo(false);
        mvc.perform(get("/api/pdis").header(HttpHeaders.AUTHORIZATION, basic("owner")))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(pdis, versoes, eventos);
    }

    @Test
    void ownerUpdatesDraftAndRecordsPreviousContent() throws Exception {
        var result = edit("owner", 11, """
                {"titulo":"Plano atualizado","objetivo":"Praticar",
                 "inicioPlanejado":"2026-02-01","fimPlanejado":"2026-11-30"}
                """).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.titulo").value("Plano atualizado"))
                .andExpect(jsonPath("$.objetivo").value("Praticar"))
                .andExpect(jsonPath("$.inicioPlanejado").value("2026-02-01"))
                .andExpect(jsonPath("$.fimPlanejado").value("2026-11-30"))
                .andReturn();
        assertDto(mapper.readTree(result.getResponse().getContentAsString()));
        verify(pdis).buscarDoTitularParaAtualizacao(11L, OWNER);
        verify(versoes).saveAndFlush(draft);
        verify(pdis, never()).saveAndFlush(any());
        var event = ArgumentCaptor.forClass(PdiEvento.class);
        verify(eventos).saveAndFlush(event.capture());
        assertEquals("EDICAO_RASCUNHO", event.getValue().getTipo());
        assertEquals(OWNER, event.getValue().getAutor().getId());
        assertEquals(21L, event.getValue().getPdiVersaoId());
        assertEquals("Rascunho privado", event.getValue().getSnapshot().get("titulo").asText());
        assertEquals("Plano atualizado", event.getValue().getDados().get("titulo").asText());
    }

    @Test
    void anonymousPutCannotReachServiceEvenWithValidCsrf() throws Exception {
        Token token = token("owner");
        mvc.perform(put("/api/pdis/11").session(token.session())
                        .header(token.header(), token.value())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(pdis, versoes, eventos);
    }

    @Test
    void authenticatedPutRequiresValidCsrf() throws Exception {
        mvc.perform(put("/api/pdis/11").header(HttpHeaders.AUTHORIZATION, basic("owner"))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
        Token token = token("owner");
        mvc.perform(put("/api/pdis/11").header(HttpHeaders.AUTHORIZATION, basic("owner"))
                        .session(token.session()).header(token.header(), "invalid")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(pdis, versoes, eventos);
    }

    @Test
    void putMissingAndOtherOwnersDraftHaveSame404Contract() throws Exception {
        var missing = edit("owner", 999, "{}").andExpect(status().isNotFound()).andReturn();
        var other = edit("other", 11, "{}").andExpect(status().isNotFound()).andReturn();
        JsonNode first = mapper.readTree(missing.getResponse().getContentAsString());
        JsonNode second = mapper.readTree(other.getResponse().getContentAsString());
        assertEquals(first.get("detail"), second.get("detail"));
        assertEquals(first.get("title"), second.get("title"));
        verify(pdis).buscarDoTitularParaAtualizacao(11L, OTHER);
        verifyNoInteractions(versoes, eventos);
    }

    @Test
    void putInvertedDatesAre400WithoutWrites() throws Exception {
        edit("owner", 11, """
                {"inicioPlanejado":"2026-12-31","fimPlanejado":"2026-01-01"}
                """).andExpect(status().isBadRequest());
        verify(versoes, never()).saveAndFlush(any());
        verifyNoInteractions(eventos);
        assertEquals("Rascunho privado", draft.getTitulo());
    }

    @Test
    void putAllowsOptionalDates() throws Exception {
        edit("owner", 11, "{\"titulo\":\"Sem datas\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.inicioPlanejado").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.fimPlanejado").value(org.hamcrest.Matchers.nullValue()));
        verify(versoes).saveAndFlush(draft);
    }

    @Test
    void putNonEditableDraftIs409WithoutWrites() throws Exception {
        draft.setStatusAprovacao(StatusAprovacaoPdi.AGUARDANDO_APROVACAO);
        draft.setSubmetidaEm(Instant.parse("2026-01-02T00:00:00Z"));
        edit("owner", 11, "{}").andExpect(status().isConflict());
        verify(versoes, never()).saveAndFlush(any());
        verifyNoInteractions(eventos);
    }

    @Test
    void stalePutWithoutExpectedVersionIs409ForRevision() throws Exception {
        draft.setNumero(2);
        edit("owner", 11, "{}").andExpect(status().isConflict());
        verify(versoes, never()).saveAndFlush(any());
        verifyNoInteractions(eventos);
    }

    @Test
    void putLockConflictIs409WithoutWrites() throws Exception {
        when(pdis.buscarDoTitularParaAtualizacao(11L, OWNER))
                .thenThrow(new org.springframework.dao.CannotAcquireLockException("Synthetic lock conflict"));
        edit("owner", 11, "{}").andExpect(status().isConflict());
        verifyNoInteractions(versoes, eventos);
    }

    @Test
    void putCannotChangeServerControlledIdentityOrLifecycle() throws Exception {
        String body = mapper.writeValueAsString(Map.ofEntries(
                Map.entry("titulo", "Somente conteudo"), Map.entry("titular", OTHER.toString()),
                Map.entry("usuarioId", OTHER.toString()), Map.entry("autorId", OTHER.toString()),
                Map.entry("id", 999), Map.entry("versaoId", 888), Map.entry("numeroVersao", 9),
                Map.entry("numero", 9), Map.entry("versaoVigenteId", 888),
                Map.entry("statusExecucao", "CONCLUIDO"), Map.entry("statusAprovacao", "APROVADO")));
        var result = edit("owner", 11, body).andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(11)).andExpect(jsonPath("$.versaoId").value(21))
                .andExpect(jsonPath("$.numeroVersao").value(1))
                .andExpect(jsonPath("$.statusExecucao").value("NAO_INICIADO"))
                .andExpect(jsonPath("$.statusAprovacao").value("RASCUNHO")).andReturn();
        assertDto(mapper.readTree(result.getResponse().getContentAsString()));
        assertEquals(OWNER, draft.getPdi().getUsuario().getId());
        assertNull(draft.getPdi().getVersaoVigenteId());
        var event = ArgumentCaptor.forClass(PdiEvento.class);
        verify(eventos).saveAndFlush(event.capture());
        assertEquals(OWNER, event.getValue().getAutor().getId());
    }

    @Test
    void putDoesNotReleaseOtherRoutes() throws Exception {
        Token token = token("owner");
        for (String path : List.of("/api/pdis/usuario/" + OWNER, "/api/usuarios/11",
                "/api/acoes/11", "/api/reunioes/11", "/api/pdis/11/aprovar")) {
            mvc.perform(put(path).header(HttpHeaders.AUTHORIZATION, basic("owner"))
                            .session(token.session()).header(token.header(), token.value())
                            .contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isForbidden());
        }
        verifyNoInteractions(pdis, versoes, eventos);
    }

    @Test
    void ownerCreatesActionAsDtoWithServerLinksAndHistory() throws Exception {
        var result = addAction("owner", 11, """
                {"titulo":"Praticar SQL","objetivo":"Aprender joins","resultadoEsperado":"Relatorio",
                 "inicioPlanejado":"2026-02-01","fimPlanejado":"2026-11-30"}
                """).andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/pdis/11/acoes/31"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.titulo").value("Praticar SQL"))
                .andExpect(jsonPath("$.objetivo").value("Aprender joins"))
                .andExpect(jsonPath("$.resultadoEsperado").value("Relatorio"))
                .andExpect(jsonPath("$.inicioPlanejado").value("2026-02-01"))
                .andExpect(jsonPath("$.fimPlanejado").value("2026-11-30"))
                .andExpect(jsonPath("$.statusExecucao").value("NAO_INICIADA")).andReturn();
        assertActionDto(mapper.readTree(result.getResponse().getContentAsString()));
        var identity = ArgumentCaptor.forClass(AcaoPDI.class);
        var content = ArgumentCaptor.forClass(AcaoPdiVersao.class);
        var event = ArgumentCaptor.forClass(PdiEvento.class);
        var order = inOrder(usuarios, pdis, versoes, acoes, acoesVersoes, eventos);
        order.verify(usuarios).findByIdForUpdate(OWNER);
        order.verify(pdis).buscarDoTitularParaAtualizacao(11L, OWNER);
        order.verify(versoes).buscarEditavelParaAtualizacao(11L);
        order.verify(acoes).saveAndFlush(identity.capture());
        order.verify(acoesVersoes).saveAndFlush(content.capture());
        order.verify(eventos).saveAndFlush(event.capture());
        assertSame(draft.getPdi(), identity.getValue().getPdi());
        assertNull(identity.getValue().getIniciadaEm());
        assertNull(identity.getValue().getConcluidaEm());
        assertNull(identity.getValue().getConcluidaPor());
        assertSame(draft, content.getValue().getPdiVersao());
        assertSame(identity.getValue(), content.getValue().getAcaoPdi());
        assertEquals(11L, content.getValue().getPdiId());
        assertEquals(21L, content.getValue().getPdiVersaoId());
        assertEquals(31L, content.getValue().getAcaoPdiId());
        assertEquals(0, content.getValue().getOrdem());
        assertEquals("ACAO_ADICIONADA", event.getValue().getTipo());
        assertEquals(OWNER, event.getValue().getAutor().getId());
        assertEquals(11L, event.getValue().getPdiId());
        assertEquals(21L, event.getValue().getPdiVersaoId());
        assertEquals(31L, event.getValue().getAcaoPdiId());
        assertSame(content.getValue(), event.getValue().getAcaoPdiVersao());
        assertEquals("Praticar SQL", event.getValue().getDados().get("titulo").asText());
        assertNull(event.getValue().getSnapshot());
        verify(pdis, never()).saveAndFlush(any());
        verify(versoes, never()).saveAndFlush(any());
        assertEquals(1, draft.getNumero());
        assertNull(draft.getPdi().getVersaoVigenteId());
    }

    @Test
    void actionListIsScopedToAuthorizedDraftVersion() throws Exception {
        var ownerResult = mvc.perform(get("/api/pdis/11/acoes").header(HttpHeaders.AUTHORIZATION, basic("owner")))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].id").value(31))
                .andExpect(jsonPath("$[0].pdiVersaoId").value(21)).andReturn();
        assertActionDto(mapper.readTree(ownerResult.getResponse().getContentAsString()).get(0));
        var otherResult = mvc.perform(get("/api/pdis/12/acoes").header(HttpHeaders.AUTHORIZATION, basic("other")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(33)).andExpect(jsonPath("$[0].pdiId").value(12)).andReturn();
        assertActionDto(mapper.readTree(otherResult.getResponse().getContentAsString()).get(0));
        verify(versoes).buscarRascunho(11L, OWNER, StatusAprovacaoPdi.RASCUNHO);
        verify(versoes).buscarRascunho(12L, OTHER, StatusAprovacaoPdi.RASCUNHO);
        verify(acoesVersoes).listarNaVersao(11L, 21L);
        verify(acoesVersoes).listarNaVersao(12L, 23L);
        verify(acoesVersoes, never()).listarNaVersao(11L, 22L);
        verifyNoInteractions(acoes, eventos);
    }

    @Test
    void ownerReadsCorrectActionAsDto() throws Exception {
        var result = mvc.perform(get("/api/pdis/11/acoes/31").header(HttpHeaders.AUTHORIZATION, basic("owner")))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.id").value(31)).andExpect(jsonPath("$.acaoVersaoId").value(41))
                .andExpect(jsonPath("$.pdiId").value(11)).andExpect(jsonPath("$.pdiVersaoId").value(21))
                .andExpect(jsonPath("$.titulo").value("Acao privada")).andReturn();
        assertActionDto(mapper.readTree(result.getResponse().getContentAsString()));
        verify(acoesVersoes).buscarNaVersao(11L, 21L, 31L);
        verifyNoInteractions(acoes, eventos);
    }

    @Test
    void anonymousRequestsCannotReachActions() throws Exception {
        mvc.perform(get("/api/pdis/11/acoes")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/pdis/11/acoes/31")).andExpect(status().isUnauthorized());
        Token token = token("owner");
        mvc.perform(post("/api/pdis/11/acoes").session(token.session())
                        .header(token.header(), token.value()).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/pdis/11/acoes").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(pdis, versoes, acoes, acoesVersoes, eventos);
    }

    @Test
    void actionPostRequiresValidCsrf() throws Exception {
        mvc.perform(post("/api/pdis/11/acoes").header(HttpHeaders.AUTHORIZATION, basic("owner"))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
        Token token = token("owner");
        mvc.perform(post("/api/pdis/11/acoes").header(HttpHeaders.AUTHORIZATION, basic("owner"))
                        .session(token.session()).header(token.header(), "invalid")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(pdis, versoes, acoes, acoesVersoes, eventos);
    }

    @Test
    void missingAndForeignPdiHaveSame404ForAllActionRoutes() throws Exception {
        var missingPost = addAction("owner", 999, "{}").andExpect(status().isNotFound()).andReturn();
        var foreignPost = addAction("other", 11, "{}").andExpect(status().isNotFound()).andReturn();
        assertEquals(mapper.readTree(missingPost.getResponse().getContentAsString()).get("detail"),
                mapper.readTree(foreignPost.getResponse().getContentAsString()).get("detail"));
        for (String suffix : List.of("/acoes", "/acoes/31")) {
            var missing = mvc.perform(get("/api/pdis/999" + suffix).header(HttpHeaders.AUTHORIZATION, basic("owner")))
                    .andExpect(status().isNotFound()).andReturn();
            var foreign = mvc.perform(get("/api/pdis/11" + suffix).header(HttpHeaders.AUTHORIZATION, basic("other")))
                    .andExpect(status().isNotFound()).andReturn();
            assertEquals(mapper.readTree(missing.getResponse().getContentAsString()).get("detail"),
                    mapper.readTree(foreign.getResponse().getContentAsString()).get("detail"));
        }
        verifyNoInteractions(acoes, acoesVersoes, eventos);
    }

    @Test
    void actionOutsideSelectedPdiOrVersionIs404() throws Exception {
        for (long actionId : new long[]{999, 32, 33}) {
            mvc.perform(get("/api/pdis/11/acoes/" + actionId).header(HttpHeaders.AUTHORIZATION, basic("owner")))
                    .andExpect(status().isNotFound());
            verify(acoesVersoes).buscarNaVersao(11L, 21L, actionId);
        }
        mvc.perform(get("/api/pdis/12/acoes/31").header(HttpHeaders.AUTHORIZATION, basic("other")))
                .andExpect(status().isNotFound());
        verify(acoesVersoes).buscarNaVersao(12L, 23L, 31L);
        verifyNoInteractions(acoes, eventos);
    }

    @Test
    void emptyActionListForAuthorizedDraftIs200() throws Exception {
        when(acoesVersoes.listarNaVersao(11L, 21L)).thenReturn(List.of());
        mvc.perform(get("/api/pdis/11/acoes").header(HttpHeaders.AUTHORIZATION, basic("owner")))
                .andExpect(status().isOk()).andExpect(content().json("[]"));
        verify(acoesVersoes).listarNaVersao(11L, 21L);
        verifyNoInteractions(acoes, eventos);
    }

    @Test
    void actionPostToNonEditablePdiIs409() throws Exception {
        draft.setStatusAprovacao(StatusAprovacaoPdi.AGUARDANDO_APROVACAO);
        draft.setSubmetidaEm(Instant.parse("2026-01-02T00:00:00Z"));
        addAction("owner", 11, "{}").andExpect(status().isConflict());
        verifyNoInteractions(acoes, acoesVersoes, eventos);
    }

    @Test
    void staleActionPostWithoutExpectedVersionIs409ForRevision() throws Exception {
        draft.setNumero(2);
        addAction("owner", 11, "{}").andExpect(status().isConflict());
        verifyNoInteractions(acoes, acoesVersoes, eventos);
    }

    @Test
    void revisionReadsRemainPrivateButLegacyWritesNeedExpectedVersion() throws Exception {
        draft.setNumero(2);
        addAction("owner", 11, "{}").andExpect(status().isConflict());
        mvc.perform(get("/api/pdis/11/acoes").header(HttpHeaders.AUTHORIZATION, basic("owner")))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].pdiVersaoId").value(21));
        mvc.perform(get("/api/pdis/11/acoes/31").header(HttpHeaders.AUTHORIZATION, basic("owner")))
                .andExpect(status().isOk());
        verifyNoInteractions(acoes, eventos);
    }

    @Test
    void actionPostRejectsInvertedDatesAndMalformedPayloads() throws Exception {
        for (String body : List.of(
                "{\"inicioPlanejado\":\"2026-12-31\",\"fimPlanejado\":\"2026-01-01\"}",
                "{\"inicioPlanejado\":\"not-a-date\"}", "{\"titulo\":{}}", "{")) {
            addAction("owner", 11, body).andExpect(status().isBadRequest());
        }
        verifyNoInteractions(acoes, acoesVersoes, eventos);
    }

    @Test
    void actionPostAllowsIncompleteDraftContent() throws Exception {
        var result = addAction("owner", 11, "{}").andExpect(status().isCreated())
                .andExpect(jsonPath("$.titulo").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.objetivo").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.resultadoEsperado").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.inicioPlanejado").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.fimPlanejado").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.ordem").value(0))
                .andExpect(jsonPath("$.statusExecucao").value("NAO_INICIADA")).andReturn();
        assertActionDto(mapper.readTree(result.getResponse().getContentAsString()));
        verify(acoesVersoes).saveAndFlush(any(AcaoPdiVersao.class));
        verify(eventos).saveAndFlush(any(PdiEvento.class));
    }

    @Test
    void actionPayloadCannotControlIdentityOwnershipVersionOrderOrState() throws Exception {
        String body = mapper.writeValueAsString(Map.ofEntries(
                Map.entry("titulo", "Conteudo permitido"), Map.entry("id", 999),
                Map.entry("acaoId", 999), Map.entry("acaoVersaoId", 888), Map.entry("pdiId", 12),
                Map.entry("pdiVersaoId", 23), Map.entry("versaoId", 23), Map.entry("numeroVersao", 2),
                Map.entry("titular", OTHER.toString()), Map.entry("usuarioId", OTHER.toString()),
                Map.entry("autorId", OTHER.toString()), Map.entry("ordem", 99),
                Map.entry("statusExecucao", "CONCLUIDA"), Map.entry("iniciadaEm", "2026-01-01T00:00:00Z")));
        var result = addAction("owner", 11, body).andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/pdis/11/acoes/31"))
                .andExpect(jsonPath("$.id").value(31)).andExpect(jsonPath("$.acaoVersaoId").value(41))
                .andExpect(jsonPath("$.pdiId").value(11)).andExpect(jsonPath("$.pdiVersaoId").value(21))
                .andExpect(jsonPath("$.ordem").value(0))
                .andExpect(jsonPath("$.statusExecucao").value("NAO_INICIADA")).andReturn();
        assertActionDto(mapper.readTree(result.getResponse().getContentAsString()));
        var identity = ArgumentCaptor.forClass(AcaoPDI.class);
        verify(acoes).saveAndFlush(identity.capture());
        assertEquals(OWNER, identity.getValue().getPdi().getUsuario().getId());
        assertNull(identity.getValue().getIniciadaEm());
        var event = ArgumentCaptor.forClass(PdiEvento.class);
        verify(eventos).saveAndFlush(event.capture());
        assertEquals(OWNER, event.getValue().getAutor().getId());
        assertEquals(21L, event.getValue().getPdiVersaoId());
    }

    @Test
    void actionReadHidesUnavailableDraft() throws Exception {
        draft.setStatusAprovacao(StatusAprovacaoPdi.AGUARDANDO_APROVACAO);
        draft.setSubmetidaEm(Instant.parse("2026-01-02T00:00:00Z"));
        mvc.perform(get("/api/pdis/11/acoes").header(HttpHeaders.AUTHORIZATION, basic("owner")))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/pdis/11/acoes/31").header(HttpHeaders.AUTHORIZATION, basic("owner")))
                .andExpect(status().isNotFound());
        verifyNoInteractions(acoes, acoesVersoes, eventos);
    }

    @Test
    void actionRoutesRejectInactiveUser() throws Exception {
        Token token = token("owner");
        owner.setAtivo(false);
        mvc.perform(post("/api/pdis/11/acoes").header(HttpHeaders.AUTHORIZATION, basic("owner"))
                        .session(token.session()).header(token.header(), token.value())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/pdis/11/acoes").header(HttpHeaders.AUTHORIZATION, basic("owner")))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/pdis/11/acoes/31").header(HttpHeaders.AUTHORIZATION, basic("owner")))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(pdis, versoes, acoes, acoesVersoes, eventos);
    }

    @Test
    void actionLockConflictReturns409WithoutWrites() throws Exception {
        when(pdis.buscarDoTitularParaAtualizacao(11L, OWNER))
                .thenThrow(new org.springframework.dao.CannotAcquireLockException("Synthetic lock conflict"));
        addAction("owner", 11, "{}").andExpect(status().isConflict());
        verifyNoInteractions(acoes, acoesVersoes, eventos);
    }

    @Test
    void actionRoutesDoNotReleaseLegacyOrFutureOperations() throws Exception {
        for (String path : List.of("/api/acoes", "/api/acoes/pdi/11", "/api/usuarios", "/api/reunioes",
                "/api/pdis/11/acoes/31/componentes")) {
            mvc.perform(get(path).header(HttpHeaders.AUTHORIZATION, basic("owner")))
                    .andExpect(status().isForbidden());
        }
        Token token = token("owner");
        for (var request : List.of(put("/api/pdis/11/acoes/31"), delete("/api/pdis/11/acoes/31"),
                post("/api/acoes"), post("/api/pdis/11/acoes/31/componentes"), post("/api/pdis/11/submeter"))) {
            mvc.perform(request.header(HttpHeaders.AUTHORIZATION, basic("owner"))
                            .session(token.session()).header(token.header(), token.value())
                            .contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isForbidden());
        }
        verifyNoInteractions(pdis, versoes, acoes, acoesVersoes, eventos);
    }

    @Test
    void demoDraftQueryPreservesVersionGuardAndDoesNotReleaseSubmission() throws Exception {
        Token token = token("owner");
        mvc.perform(put("/api/pdis/11?versaoId=20").header(HttpHeaders.AUTHORIZATION, basic("owner"))
                        .session(token.session()).header(token.header(), token.value())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isConflict());
        verifyNoInteractions(eventos);
        // Basic authentication invalidates the previous CSRF token after each write.
        token = token("owner");
        mvc.perform(put("/api/pdis/11?versaoId=21").header(HttpHeaders.AUTHORIZATION, basic("owner"))
                        .session(token.session()).header(token.header(), token.value())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk())
                .andExpect(result -> assertEquals("versaoId=21", result.getRequest().getQueryString()));
        token = token("owner");
        mvc.perform(post("/api/pdis/11/submeter?versaoId=21").header(HttpHeaders.AUTHORIZATION, basic("owner"))
                        .session(token.session()).header(token.header(), token.value()))
                .andExpect(status().isForbidden());
        verify(eventos).saveAndFlush(any(PdiEvento.class));
    }

    private org.springframework.test.web.servlet.ResultActions addAction(String login, long pdiId, String body) throws Exception {
        Token token = token(login);
        return mvc.perform(post("/api/pdis/" + pdiId + "/acoes").header(HttpHeaders.AUTHORIZATION, basic(login))
                .session(token.session()).header(token.header(), token.value())
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private static AcaoPdiVersao action(PdiVersao version, Long actionId, Long contentId, String title) {
        AcaoPDI identity = new AcaoPDI();
        ReflectionTestUtils.setField(identity, "id", actionId);
        identity.setPdi(version.getPdi());
        AcaoPdiVersao content = new AcaoPdiVersao();
        ReflectionTestUtils.setField(content, "id", contentId);
        content.definirVinculos(version, identity);
        content.setTitulo(title);
        return content;
    }

    private static void assertActionDto(JsonNode json) {
        Set<String> names = new HashSet<>();
        json.fieldNames().forEachRemaining(names::add);
        assertEquals(Set.of("id", "acaoVersaoId", "pdiId", "pdiVersaoId", "titulo", "objetivo",
                "resultadoEsperado", "inicioPlanejado", "fimPlanejado", "ordem", "statusExecucao"), names);
        json.forEach(value -> assertFalse(value.isObject() || value.isArray()));
    }

    private org.springframework.test.web.servlet.ResultActions edit(String login, long id, String body) throws Exception {
        Token token = token(login);
        return mvc.perform(put("/api/pdis/" + id).header(HttpHeaders.AUTHORIZATION, basic(login))
                .session(token.session()).header(token.header(), token.value())
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private Token token(String login) throws Exception {
        MvcResult result = mvc.perform(get("/api/csrf").header(HttpHeaders.AUTHORIZATION, basic(login)))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store")).andReturn();
        JsonNode json = mapper.readTree(result.getResponse().getContentAsString());
        MockHttpSession session = (MockHttpSession) result.getRequest().getSession(false);
        assertNotNull(session);
        return new Token(session, json.get("headerName").asText(), json.get("token").asText());
    }

    private static String basic(String login) {
        return "Basic " + Base64.getEncoder().encodeToString((login + ":" + PASSWORD).getBytes(StandardCharsets.UTF_8));
    }

    private static Usuario usuario(UUID id) {
        Usuario user = new Usuario();
        ReflectionTestUtils.setField(user, "id", id);
        user.setAtivo(true);
        return user;
    }

    private static PdiVersao draft(Usuario user, Long id, Long versionId) {
        PDI plan = new PDI();
        ReflectionTestUtils.setField(plan, "id", id);
        plan.setUsuario(user);
        PdiVersao version = new PdiVersao();
        ReflectionTestUtils.setField(version, "id", versionId);
        version.setPdi(plan);
        version.setNumero(1);
        version.setTitulo("Rascunho privado");
        return version;
    }

    private static void assertDto(JsonNode json) {
        Set<String> names = new HashSet<>();
        json.fieldNames().forEachRemaining(names::add);
        assertEquals(Set.of("id", "versaoId", "numeroVersao", "versaoVigenteId", "statusExecucao",
                "statusAprovacao", "titulo", "objetivo", "inicioPlanejado", "fimPlanejado",
                "criadoEm", "versaoCriadaEm"), names);
        assertTrue(json.get("versaoVigenteId").isNull());
    }

    private record Token(MockHttpSession session, String header, String value) { }
}
