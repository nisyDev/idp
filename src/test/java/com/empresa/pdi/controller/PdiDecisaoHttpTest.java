package com.empresa.pdi.controller;

import com.empresa.pdi.dto.PdiDecisaoRequest;
import com.empresa.pdi.dto.PdiDecisaoResponse;
import com.empresa.pdi.dto.PdiVersaoResponse;
import com.empresa.pdi.dto.AcaoPdiResponse;
import com.empresa.pdi.enums.StatusExecucaoAcao;
import com.empresa.pdi.exception.PdiRascunhoNaoEditavelException;
import com.empresa.pdi.enums.StatusAprovacaoPdi;
import com.empresa.pdi.enums.StatusExecucaoPdi;
import com.empresa.pdi.enums.PerfilUsuario;
import com.empresa.pdi.model.Usuario;
import com.empresa.pdi.repository.UsuarioCredencialLocalRepository;
import com.empresa.pdi.repository.UsuarioRepository;
import com.empresa.pdi.security.*;
import com.empresa.pdi.service.PdiService;
import com.empresa.pdi.service.AcaoPdiService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Real HTTP security and exception handling; service decisions are covered separately.
 * No datasource, JPA bootstrap, external authentication or database is used.
 */
@WebMvcTest(controllers = {PDIController.class, AcaoPDIController.class, CsrfController.class}, excludeAutoConfiguration = {
        DataSourceAutoConfiguration.class, HibernateJpaAutoConfiguration.class, FlywayAutoConfiguration.class})
@Import({HttpSecurityConfig.class, LocalAuthenticationConfig.class, LocalHttpSecurityConfig.class,
        PdiExceptionHandler.class})
@ActiveProfiles(value = "auth-local", inheritProfiles = false)
class PdiDecisaoHttpTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @MockBean PdiService service;
    @MockBean AcaoPdiService acoes;
    @MockBean UsuarioRepository usuarios;
    @MockBean UsuarioCredencialLocalRepository credenciais;

    @Test
    void anonymousDecisionWithValidCsrfIs401() throws Exception {
        Token token = token(null);
        mvc.perform(decision("aprovar", token, "{}"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }

    @Test
    void collaboratorCannotUseDecisionOrPendingRoutes() throws Exception {
        Token token = token(PerfilUsuario.COLABORADOR);
        for (String operation : List.of("aprovar", "reprovar")) {
            mvc.perform(decision(operation, token, "{\"justificativa\":\"Revisar\"}"))
                    .andExpect(status().isForbidden());
        }
        mvc.perform(get("/api/pdis/pendentes").session(token.session()))
                .andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }

    @Test
    void decisionsRequireValidSessionCsrf() throws Exception {
        Token token = token(PerfilUsuario.GESTOR);
        mvc.perform(post("/api/pdis/11/versoes/21/aprovar").session(token.session())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/pdis/11/versoes/21/reprovar").session(token.session())
                        .header(token.header(), "invalid").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"justificativa\":\"Revisar\"}"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }

    @Test
    void managerDecisionPassesExactPdiVersionAndReasonToService() throws Exception {
        Token token = token(PerfilUsuario.GESTOR);
        mvc.perform(decision("aprovar", token, "{\"justificativa\":\"Conforme\"}"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"));
        verify(service).aprovar(11L, 21L, new PdiDecisaoRequest("Conforme"));
        mvc.perform(decision("reprovar", token, "{\"justificativa\":\"Detalhar entregas\"}"))
                .andExpect(status().isOk());
        verify(service).reprovar(11L, 21L, new PdiDecisaoRequest("Detalhar entregas"));
    }

    @Test
    void rejectionResponseExposesDecisionAndOnlyNewDraftIdentity() throws Exception {
        Token token = token(PerfilUsuario.GESTOR);
        UUID owner = UUID.randomUUID();
        UUID manager = UUID.randomUUID();
        var version = new PdiVersaoResponse(11L, 21L, 1, owner, manager, null,
                StatusExecucaoPdi.NAO_INICIADO, StatusAprovacaoPdi.REPROVADO, "Plano submetido", null,
                null, null, null, null, Instant.parse("2026-01-01T00:00:00Z"), manager,
                Instant.parse("2026-01-02T00:00:00Z"), manager, "Detalhar entregas");
        when(service.reprovar(eq(11L), eq(21L), any()))
                .thenReturn(new PdiDecisaoResponse(version, 22L, 2));
        mvc.perform(decision("reprovar", token, "{\"justificativa\":\"Detalhar entregas\"}"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.versaoDecidida.versaoId").value(21))
                .andExpect(jsonPath("$.versaoDecidida.statusAprovacao").value("REPROVADO"))
                .andExpect(jsonPath("$.versaoDecidida.decididoPorId").value(manager.toString()))
                .andExpect(jsonPath("$.versaoDecidida.justificativaDecisao").value("Detalhar entregas"))
                .andExpect(jsonPath("$.novaVersaoId").value(22)).andExpect(jsonPath("$.novoNumero").value(2))
                .andExpect(jsonPath("$.novaVersao").doesNotExist())
                .andExpect(jsonPath("$.snapshot").doesNotExist());
    }

    @Test
    void authorizedRhHttpRoleStillDependsOnServiceAuthorization() throws Exception {
        Token token = token(PerfilUsuario.RH_ADMIN);
        when(service.aprovar(eq(11L), eq(21L), any()))
                .thenThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, "internal permission detail"));
        String body = mvc.perform(decision("aprovar", token, "{}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.status").value(403))
                .andReturn().getResponse().getContentAsString();
        assertFalse(body.contains("internal permission detail"));
    }

    @Test
    void businessStatusesArePreservedAndInternalDetailsAreSanitized() throws Exception {
        Token token = token(PerfilUsuario.GESTOR);
        for (HttpStatus status : List.of(HttpStatus.BAD_REQUEST, HttpStatus.NOT_FOUND, HttpStatus.CONFLICT)) {
            doThrow(new ResponseStatusException(status, "sensitive synthetic internal detail"))
                    .when(service).reprovar(eq(11L), eq(21L), any());
            String body = mvc.perform(decision("reprovar", token, "{\"justificativa\":\"Revisar\"}"))
                    .andExpect(status().is(status.value())).andExpect(jsonPath("$.status").value(status.value()))
                    .andReturn().getResponse().getContentAsString();
            assertFalse(body.contains("sensitive synthetic internal detail"));
        }
    }

    @Test
    void persistenceFailureIs500WithoutExceptionDetails() throws Exception {
        Token token = token(PerfilUsuario.GESTOR);
        when(service.reprovar(eq(11L), eq(21L), any()))
                .thenThrow(new IllegalStateException("synthetic database detail"));
        String body = mvc.perform(decision("reprovar", token, "{\"justificativa\":\"Revisar\"}"))
                .andExpect(status().isInternalServerError()).andExpect(jsonPath("$.status").value(500))
                .andExpect(jsonPath("$.trace").doesNotExist()).andReturn().getResponse().getContentAsString();
        assertFalse(body.contains("synthetic database detail"));
    }

    @Test
    void malformedDecisionDoesNotInvokeService() throws Exception {
        Token token = token(PerfilUsuario.GESTOR);
        mvc.perform(decision("reprovar", token, "{" )).andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @Test
    void legacyUpdatePropagatesExpectedVersionQueryParameter() throws Exception {
        Token token = token(PerfilUsuario.COLABORADOR);
        mvc.perform(put("/api/pdis/11?versaoId=22").session(token.session())
                        .header(token.header(), token.value()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"titulo\":\"Corrigido\"}"))
                .andExpect(status().isOk())
                .andExpect(result -> assertEquals("versaoId=22", result.getRequest().getQueryString()));
        verify(service).atualizarRascunho(eq(11L), any(), eq(22L));
        mvc.perform(post("/api/pdis/11/submeter?versaoId=22").session(token.session())
                        .header(token.header(), token.value()))
                .andExpect(status().isOk())
                .andExpect(result -> assertEquals("versaoId=22", result.getRequest().getQueryString()));
        verify(service).submeter(11L, 22L);
    }

    @Test
    void actionCreationPropagatesRealVersionQueryWithoutChangingServerControlledIdentity() throws Exception {
        Token token = token(PerfilUsuario.COLABORADOR);
        when(acoes.criar(eq(11L), any(), eq(22L))).thenReturn(new AcaoPdiResponse(
                31L, 41L, 11L, 22L, "Acao", null, null, null, null, 0, StatusExecucaoAcao.NAO_INICIADA));
        mvc.perform(post("/api/pdis/11/acoes?versaoId=22").session(token.session())
                        .header(token.header(), token.value()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"titulo\":\"Acao\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.pdiVersaoId").value(22))
                .andExpect(result -> assertEquals("versaoId=22", result.getRequest().getQueryString()));
        verify(acoes).criar(eq(11L), any(), eq(22L));
        verifyNoInteractions(service);
    }

    @Test
    void missingOrStaleVersionReachesBusinessConflictInsteadOfHttpDenial() throws Exception {
        Token token = token(PerfilUsuario.COLABORADOR);
        doThrow(new PdiRascunhoNaoEditavelException()).when(service)
                .atualizarRascunho(eq(11L), any(), nullable(Long.class));
        doThrow(new PdiRascunhoNaoEditavelException()).when(service).submeter(eq(11L), nullable(Long.class));
        for (String query : List.of("", "?versaoId=20")) {
            mvc.perform(put("/api/pdis/11" + query).session(token.session())
                            .header(token.header(), token.value()).contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isConflict());
            mvc.perform(post("/api/pdis/11/submeter" + query).session(token.session())
                            .header(token.header(), token.value()))
                    .andExpect(status().isConflict());
        }
        verify(service).atualizarRascunho(eq(11L), any(), isNull());
        verify(service).atualizarRascunho(eq(11L), any(), eq(20L));
        verify(service).submeter(11L, null);
        verify(service).submeter(11L, 20L);
    }

    @Test
    void nonNumericVersionQueryIs400BeforePdiOrActionService() throws Exception {
        Token token = token(PerfilUsuario.COLABORADOR);
        for (var request : List.of(put("/api/pdis/11?versaoId=invalid"),
                post("/api/pdis/11/submeter?versaoId=invalid"), post("/api/pdis/11/acoes?versaoId=invalid"))) {
            mvc.perform(request.session(token.session()).header(token.header(), token.value())
                            .contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isBadRequest());
        }
        verifyNoInteractions(service, acoes);
    }

    @Test
    void queryDoesNotAuthorizeUnknownPathsInvalidIdsOrUnreleasedHttpMethods() throws Exception {
        Token token = token(PerfilUsuario.COLABORADOR);
        for (var request : List.of(put("/api/pdis/invalid?versaoId=22"),
                put("/api/pdis/11/acoes/invalid?versaoId=22"), patch("/api/pdis/11?versaoId=22"),
                post("/api/pdis/11/acoes/31/componentes?versaoId=22"))) {
            mvc.perform(request.session(token.session()).header(token.header(), token.value())
                            .contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isForbidden());
        }
        verifyNoInteractions(service, acoes);
    }

    private MockHttpServletRequestBuilder decision(String operation, Token token, String body) {
        return post("/api/pdis/11/versoes/21/" + operation).session(token.session())
                .header(token.header(), token.value()).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private Token token(PerfilUsuario profile) throws Exception {
        MockHttpSession session = new MockHttpSession();
        if (profile != null) {
            Usuario user = new Usuario();
            ReflectionTestUtils.setField(user, "id", UUID.randomUUID());
            user.setNome("Pessoa ficticia");
            user.setEmail("pessoa@talentsynk.invalid");
            user.setPerfilAcesso(profile);
            UsuarioPrincipal principal = new UsuarioPrincipal(user);
            var context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                    principal, null, principal.getAuthorities()));
            session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, context);
        }
        var result = mvc.perform(get("/api/csrf").session(session)).andExpect(status().isOk()).andReturn();
        JsonNode value = mapper.readTree(result.getResponse().getContentAsString());
        return new Token(session, value.path("headerName").asText(), value.path("token").asText());
    }

    private record Token(MockHttpSession session, String header, String value) {}
}
