package com.empresa.pdi.controller;

import com.empresa.pdi.enums.PerfilUsuario;
import com.empresa.pdi.enums.StatusAprovacaoPdi;
import com.empresa.pdi.model.PDI;
import com.empresa.pdi.model.PdiVersao;
import com.empresa.pdi.model.Usuario;
import com.empresa.pdi.model.UsuarioCredencialLocal;
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
import com.empresa.pdi.service.IdentidadeAutenticadaService;
import com.empresa.pdi.service.PdiService;
import com.empresa.pdi.service.AcaoPdiService;
import com.empresa.pdi.service.PdiAcessoService;
import com.empresa.pdi.repository.AcaoPDIRepository;
import com.empresa.pdi.repository.AcaoPdiVersaoRepository;
import jakarta.persistence.EntityManager;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManagerFactory;
import jakarta.servlet.SessionTrackingMode;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.web.servlet.ServletContextInitializer;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.dao.InvalidDataAccessResourceUsageException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
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

/** Real local login, filters, identity adapter and PDI service with mocked repositories only.
 * MockMvc does not run a servlet container or emit its real session Set-Cookie headers.
 */
@WebMvcTest(controllers = {PDIController.class, CsrfController.class, AuthController.class}, excludeAutoConfiguration = {
        DataSourceAutoConfiguration.class, HibernateJpaAutoConfiguration.class, FlywayAutoConfiguration.class})
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@Import({HttpSecurityConfig.class, LocalAuthenticationConfig.class, LocalHttpSecurityConfig.class,
        SecurityContextUsuarioAutenticadoProvider.class, PdiService.class, PdiAcessoService.class, PdiExceptionHandler.class,
        IdentidadeAutenticadaService.class})
@ActiveProfiles(value = "auth-local", inheritProfiles = false)
class LocalAuthenticationHttpTest {
    private static final UUID OWNER = UUID.randomUUID();
    private static final UUID OTHER = UUID.randomUUID();
    private static final String OWNER_EMAIL = "titular@talentsynk.invalid";
    private static final String OTHER_EMAIL = "administrador@talentsynk.invalid";
    // Ephemeral synthetic credentials, never read from configuration, logged or persisted.
    private static final String PASSWORD = UUID.randomUUID().toString();
    private static final String PASSWORD_HASH = new BCryptPasswordEncoder(12).encode(PASSWORD);

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired ApplicationContext context;
    @Autowired @Qualifier("localSessionCookies") ServletContextInitializer sessionCookies;
    @MockBean AcaoPdiService acaoService;
    @MockBean EntityManager entityManager;
    @MockBean AcaoPDIRepository acoes;
    @MockBean AcaoPdiVersaoRepository acoesVersoes;
    @MockBean UsuarioRepository usuarios;
    @MockBean UsuarioCredencialLocalRepository credenciais;
    @MockBean PDIRepository pdis;
    @MockBean PdiVersaoRepository versoes;
    @MockBean PdiEventoRepository eventos;

    private Usuario owner;

    @BeforeEach
    void fixtures() {
        assertTrue(context.getBeansOfType(DataSource.class).isEmpty(), "O contexto não pode ter datasource");
        assertTrue(context.getBeansOfType(EntityManagerFactory.class).isEmpty(), "O contexto não pode carregar JPA");
        owner = usuario(OWNER, OWNER_EMAIL, PerfilUsuario.COLABORADOR);
        // Even an administrative profile cannot read another user's private draft.
        Usuario other = usuario(OTHER, OTHER_EMAIL, PerfilUsuario.RH_ADMIN);
        UsuarioCredencialLocal ownerCredential = new UsuarioCredencialLocal(owner, PASSWORD_HASH);
        UsuarioCredencialLocal otherCredential = new UsuarioCredencialLocal(other, PASSWORD_HASH);
        when(usuarios.findByEmailIgnoreCase(anyString())).thenAnswer(call -> {
            String email = call.getArgument(0);
            return OWNER_EMAIL.equalsIgnoreCase(email) ? Optional.of(owner)
                    : OTHER_EMAIL.equalsIgnoreCase(email) ? Optional.of(other) : Optional.empty();
        });
        when(usuarios.findById(any(UUID.class))).thenAnswer(call -> OWNER.equals(call.getArgument(0))
                ? Optional.of(owner) : OTHER.equals(call.getArgument(0)) ? Optional.of(other) : Optional.empty());
        when(credenciais.findById(any(UUID.class))).thenAnswer(call -> OWNER.equals(call.getArgument(0))
                ? Optional.of(ownerCredential)
                : OTHER.equals(call.getArgument(0)) ? Optional.of(otherCredential) : Optional.empty());
        List<PdiVersao> drafts = List.of(draft(owner, 11L, 21L), draft(other, 12L, 22L));
        // These mocks implement the existing repository's owner scope; no SQL is executed.
        when(versoes.listarRascunhos(any(UUID.class), eq(StatusAprovacaoPdi.RASCUNHO)))
                .thenAnswer(call -> drafts.stream()
                        .filter(value -> value.getPdi().getUsuario().getId().equals(call.getArgument(0))).toList());
        when(versoes.buscarRascunho(anyLong(), any(UUID.class), eq(StatusAprovacaoPdi.RASCUNHO)))
                .thenAnswer(call -> drafts.stream()
                        .filter(value -> value.getPdi().getId().equals(call.getArgument(0))
                                && value.getPdi().getUsuario().getId().equals(call.getArgument(1))).findFirst());
    }

    @AfterEach
    void noPersistenceWrites() {
        verify(pdis, never()).save(any());
        verify(pdis, never()).saveAndFlush(any());
        // A submission ownership check may read the scalar owner ID, but every read
        // must be explicitly verified by its test. Unverified reads or writes fail.
        verifyNoMoreInteractions(pdis);
        verifyNoInteractions(eventos, acoes, acoesVersoes);
        verify(versoes, never()).saveAndFlush(any());
        verify(usuarios, never()).save(any());
        verify(credenciais, never()).save(any());
    }

    @Test
    void csrfIsAvailableBeforeLoginWithoutCredentials() throws Exception {
        Token token = token(null);
        assertFalse(token.session().isInvalid());
        assertNull(token.session().getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY));
        mvc.perform(get("/api/pdis").session(token.session()))
                .andExpect(status().isUnauthorized()).andExpect(header().doesNotExist(HttpHeaders.LOCATION));
        verifyNoInteractions(usuarios, credenciais, versoes);
    }

    @Test
    void validEmailAndPasswordReturn204AndStoreVerifiedIdentity() throws Exception {
        Token token = token(null);
        MvcResult result = login(token, OWNER_EMAIL, PASSWORD)
                .andExpect(status().isNoContent()).andExpect(content().string(""))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(header().doesNotExist(HttpHeaders.LOCATION))
                .andExpect(header().doesNotExist(HttpHeaders.WWW_AUTHENTICATE)).andReturn();
        UsuarioPrincipal principal = principal(session(result));
        assertTrue(OWNER.equals(principal.getUsuarioId()), "O UUID deve vir do usuário encontrado no servidor");
        assertEquals(OWNER_EMAIL, principal.getUsername());
        assertEquals(PerfilUsuario.COLABORADOR, principal.getPerfilAcesso());
        assertEquals(List.of("ROLE_COLABORADOR"), principal.getAuthorities().stream()
                .map(authority -> authority.getAuthority()).toList());
        assertTrue(principal.getPassword() == null, "O hash deve ser apagado após a autenticação");
        verify(usuarios).findByEmailIgnoreCase(OWNER_EMAIL);
        verify(credenciais).findById(OWNER);
    }

    @Test
    void incorrectPasswordHasGeneric401Response() throws Exception {
        assertLoginRejected(OWNER_EMAIL, PASSWORD + "-incorreta");
        verify(credenciais).findById(OWNER);
    }

    @Test
    void nonexistentUserHasSameGeneric401Response() throws Exception {
        assertLoginRejected("inexistente@talentsynk.invalid", PASSWORD);
        verifyNoInteractions(credenciais);
    }

    @Test
    void inactiveUserHasSameGeneric401Response() throws Exception {
        owner.setAtivo(false);
        assertLoginRejected(OWNER_EMAIL, PASSWORD);
        verifyNoInteractions(credenciais);
    }

    @Test
    void userWithoutLocalCredentialHasSameGeneric401Response() throws Exception {
        when(credenciais.findById(OWNER)).thenReturn(Optional.empty());
        assertLoginRejected(OWNER_EMAIL, PASSWORD);
        verify(credenciais).findById(OWNER);
    }

    @Test
    void loginWithoutCsrfIsForbiddenBeforeCredentialLookup() throws Exception {
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("email", OWNER_EMAIL).param("password", PASSWORD))
                .andExpect(status().isForbidden()).andExpect(header().doesNotExist(HttpHeaders.LOCATION));
        verifyNoInteractions(usuarios, credenciais, versoes);
    }

    @Test
    void loginWithInvalidCsrfIsForbiddenBeforeCredentialLookup() throws Exception {
        Token token = token(null);
        mvc.perform(post("/api/auth/login").session(token.session())
                        .header(token.header(), "token-invalido")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("email", OWNER_EMAIL).param("password", PASSWORD))
                .andExpect(status().isForbidden()).andExpect(header().doesNotExist(HttpHeaders.LOCATION));
        verifyNoInteractions(usuarios, credenciais, versoes);
    }

    @Test
    void sessionReadsPdisWithoutResendingCredentials() throws Exception {
        MockHttpSession session = authenticatedSession(OWNER_EMAIL);
        mvc.perform(get("/api/pdis").session(session))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(11));
        MvcResult result = mvc.perform(get("/api/pdis/11").session(session))
                .andExpect(status().isOk()).andExpect(jsonPath("$.versaoId").value(21)).andReturn();
        assertDraftDto(mapper.readTree(result.getResponse().getContentAsString()));
        // The second and third requests must reuse the session, not authenticate again.
        verify(usuarios, times(1)).findByEmailIgnoreCase(OWNER_EMAIL);
        verify(credenciais, times(1)).findById(OWNER);
        verify(versoes).listarRascunhos(OWNER, StatusAprovacaoPdi.RASCUNHO);
        verify(versoes).buscarRascunho(11L, OWNER, StatusAprovacaoPdi.RASCUNHO);
    }

    @Test
    void loginChangesSessionIdAndPreservesSessionAttributes() throws Exception {
        Token token = token(null);
        String originalId = token.session().getId();
        token.session().setAttribute("test-flow-marker", "preservado");
        MvcResult result = login(token, OWNER_EMAIL, PASSWORD).andExpect(status().isNoContent()).andReturn();
        MockHttpSession authenticated = session(result);
        assertFalse(originalId.equals(authenticated.getId()), "O identificador deve mudar no login");
        assertFalse(authenticated.isInvalid());
        assertEquals("preservado", authenticated.getAttribute("test-flow-marker"));
        principal(authenticated);
    }

    @Test
    void loginRenewsStoredCsrfAndRejectsPreLoginToken() throws Exception {
        Token before = token(null);
        MockHttpSession authenticated = session(login(before, OWNER_EMAIL, PASSWORD)
                .andExpect(status().isNoContent()).andReturn());
        Token after = token(authenticated);
        // Masked response tokens may differ even without renewal; compare stored tokens too.
        assertFalse(before.storedValue().equals(after.storedValue()), "O token armazenado deve ser renovado");
        mvc.perform(post("/api/auth/logout").session(authenticated).header(before.header(), before.value()))
                .andExpect(status().isForbidden());
        assertFalse(authenticated.isInvalid());
        mvc.perform(post("/api/auth/logout").session(authenticated).header(after.header(), after.value()))
                .andExpect(status().isNoContent());
        assertTrue(authenticated.isInvalid());
    }

    @Test
    void logoutWithValidCsrfInvalidatesSessionWithoutRedirect() throws Exception {
        MockHttpSession authenticated = authenticatedSession(OWNER_EMAIL);
        Token token = token(authenticated);
        SecurityContext security = assertInstanceOf(SecurityContext.class,
                authenticated.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY));
        mvc.perform(post("/api/auth/logout").session(authenticated).header(token.header(), token.value()))
                .andExpect(status().isNoContent()).andExpect(content().string(""))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(header().doesNotExist(HttpHeaders.LOCATION));
        assertTrue(authenticated.isInvalid(), "O logout deve invalidar a sessão no servidor");
        assertTrue(security.getAuthentication() == null, "O logout deve limpar a autenticação do contexto");
        assertThrows(IllegalStateException.class,
                () -> authenticated.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY));
        // A new request is anonymous; do not reuse an invalid MockHttpSession.
        mvc.perform(get("/api/pdis")).andExpect(status().isUnauthorized());
    }

    @Test
    void csrfCanBeObtainedAgainAfterLogoutAndUsedForAnotherLogin() throws Exception {
        MockHttpSession authenticated = authenticatedSession(OWNER_EMAIL);
        Token beforeLogout = token(authenticated);
        mvc.perform(post("/api/auth/logout").session(authenticated)
                        .header(beforeLogout.header(), beforeLogout.value()))
                .andExpect(status().isNoContent());
        assertTrue(authenticated.isInvalid());
        Token afterLogout = token(null);
        assertNotSame(authenticated, afterLogout.session());
        assertNull(afterLogout.session().getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY));
        assertFalse(beforeLogout.storedValue().equals(afterLogout.storedValue()),
                "O token da sessão encerrada não pode ser reutilizado");
        mvc.perform(post("/api/auth/login").session(afterLogout.session())
                        .header(beforeLogout.header(), beforeLogout.value())
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("email", OWNER_EMAIL).param("password", PASSWORD))
                .andExpect(status().isForbidden());
        login(afterLogout, OWNER_EMAIL, PASSWORD).andExpect(status().isNoContent());
    }

    @Test
    void logoutWithoutOrWithInvalidCsrfKeepsAuthenticatedSession() throws Exception {
        MockHttpSession authenticated = authenticatedSession(OWNER_EMAIL);
        Token token = token(authenticated);
        mvc.perform(post("/api/auth/logout").session(authenticated)).andExpect(status().isForbidden());
        mvc.perform(post("/api/auth/logout").session(authenticated).header(token.header(), "token-invalido"))
                .andExpect(status().isForbidden());
        assertFalse(authenticated.isInvalid());
        mvc.perform(get("/api/pdis").session(authenticated)).andExpect(status().isOk());
    }

    @Test
    void httpBasicDoesNotAuthenticateInLocalProfile() throws Exception {
        String basic = "Basic " + Base64.getEncoder()
                .encodeToString((OWNER_EMAIL + ":" + PASSWORD).getBytes(StandardCharsets.UTF_8));
        mvc.perform(get("/api/pdis").header(HttpHeaders.AUTHORIZATION, basic))
                .andExpect(status().isUnauthorized())
                .andExpect(header().doesNotExist(HttpHeaders.WWW_AUTHENTICATE))
                .andExpect(header().doesNotExist(HttpHeaders.LOCATION));
        verifyNoInteractions(usuarios, credenciais, versoes);
    }

    @Test
    void eachAuthenticatedUserListsOnlyTheirOwnDrafts() throws Exception {
        MockHttpSession ownerSession = authenticatedSession(OWNER_EMAIL);
        MockHttpSession otherSession = authenticatedSession(OTHER_EMAIL);
        MvcResult first = mvc.perform(get("/api/pdis").session(ownerSession))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(11)).andReturn();
        MvcResult second = mvc.perform(get("/api/pdis").session(otherSession))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(12)).andReturn();
        assertDraftDto(mapper.readTree(first.getResponse().getContentAsString()).get(0));
        assertDraftDto(mapper.readTree(second.getResponse().getContentAsString()).get(0));
        verify(versoes).listarRascunhos(OWNER, StatusAprovacaoPdi.RASCUNHO);
        verify(versoes).listarRascunhos(OTHER, StatusAprovacaoPdi.RASCUNHO);
        assertEquals(PerfilUsuario.RH_ADMIN, principal(otherSession).getPerfilAcesso());
    }

    @Test
    void otherOwnersDraftAndMissingDraftHaveSame404Contract() throws Exception {
        MockHttpSession otherSession = authenticatedSession(OTHER_EMAIL);
        MvcResult forbidden = mvc.perform(get("/api/pdis/11").session(otherSession))
                .andExpect(status().isNotFound()).andReturn();
        MvcResult missing = mvc.perform(get("/api/pdis/999").session(otherSession))
                .andExpect(status().isNotFound()).andReturn();
        JsonNode first = mapper.readTree(forbidden.getResponse().getContentAsString());
        JsonNode second = mapper.readTree(missing.getResponse().getContentAsString());
        assertEquals(first.get("title"), second.get("title"));
        assertEquals(first.get("detail"), second.get("detail"));
        verify(versoes).buscarRascunho(11L, OTHER, StatusAprovacaoPdi.RASCUNHO);
        verify(versoes).buscarRascunho(999L, OTHER, StatusAprovacaoPdi.RASCUNHO);
    }

    @Test
    void administrativeLegacyAndFutureRoutesStayForbidden() throws Exception {
        MockHttpSession authenticated = authenticatedSession(OTHER_EMAIL);
        for (String path : List.of("/api/usuarios", "/api/admin", "/api/rh", "/api/reunioes",
                "/api/acoes", "/api/pdis/usuario/" + OWNER, "/login",
                "/api/auth/login", "/api/auth/logout")) {
            mvc.perform(get(path).session(authenticated)).andExpect(status().isForbidden())
                    .andExpect(header().doesNotExist(HttpHeaders.LOCATION));
        }
        Token token = token(authenticated);
        mvc.perform(post("/api/usuarios").session(authenticated).header(token.header(), token.value())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
        assertFalse(authenticated.isInvalid(), "GET logout não deve encerrar a sessão");
        verifyNoInteractions(versoes);
    }

    @Test
    void submissionWithRealQueryHidesForeignAndMissingPdisFromAuthenticatedRh() throws Exception {
        MockHttpSession authenticated = authenticatedSession(OTHER_EMAIL);
        Token token = token(authenticated);
        when(pdis.buscarTitularId(11L)).thenReturn(Optional.of(OWNER));
        when(pdis.buscarTitularId(999L)).thenReturn(Optional.empty());

        MvcResult foreign = mvc.perform(post("/api/pdis/11/submeter?versaoId=21")
                        .session(authenticated).header(token.header(), token.value()))
                .andExpect(status().isNotFound()).andReturn();
        MvcResult missing = mvc.perform(post("/api/pdis/999/submeter?versaoId=21")
                        .session(authenticated).header(token.header(), token.value()))
                .andExpect(status().isNotFound()).andReturn();

        JsonNode foreignBody = mapper.readTree(foreign.getResponse().getContentAsString());
        JsonNode missingBody = mapper.readTree(missing.getResponse().getContentAsString());
        assertEquals(foreignBody.path("title"), missingBody.path("title"));
        assertEquals(foreignBody.path("detail"), missingBody.path("detail"));
        assertEquals("versaoId=21", foreign.getRequest().getQueryString());
        verify(pdis).buscarTitularId(11L);
        verify(pdis).buscarTitularId(999L);
        verifyNoInteractions(versoes);
    }

    @Test
    void anonymousSubmissionWithRealQueryRequiresAuthenticationEvenWithValidCsrf() throws Exception {
        Token token = token(null);

        mvc.perform(post("/api/pdis/11/submeter?versaoId=21")
                        .session(token.session()).header(token.header(), token.value()))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(usuarios, credenciais, pdis, versoes);
    }

    @Test
    void submissionWithRealQueryStillRequiresValidCsrf() throws Exception {
        MockHttpSession authenticated = authenticatedSession(OWNER_EMAIL);
        Token token = token(authenticated);

        mvc.perform(post("/api/pdis/11/submeter?versaoId=21").session(authenticated))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/pdis/11/submeter?versaoId=21")
                        .session(authenticated).header(token.header(), "invalid"))
                .andExpect(status().isForbidden());

        verifyNoInteractions(pdis, versoes);
    }

    @Test
    void cookieInitializerSetsHttpOnlySameSiteAndCookieOnlyTracking() throws Exception {
        // MockMvc has no container cookie emission: exercise the real initializer directly.
        for (boolean secureAlreadyConfigured : List.of(false, true)) {
            MockServletContext servletContext = new MockServletContext();
            servletContext.getSessionCookieConfig().setSecure(secureAlreadyConfigured);
            sessionCookies.onStartup(servletContext);
            assertTrue(servletContext.getSessionCookieConfig().isHttpOnly());
            assertEquals("Lax", servletContext.getSessionCookieConfig().getAttribute("SameSite"));
            assertEquals(Set.of(SessionTrackingMode.COOKIE), servletContext.getEffectiveSessionTrackingModes());
            assertEquals(secureAlreadyConfigured, servletContext.getSessionCookieConfig().isSecure(),
                    "O inicializador não deve rebaixar uma configuração Secure existente");
        }
    }

    @Test
    void userDeactivatedAfterLoginCannotReadPdisWithExistingSession() throws Exception {
        MockHttpSession authenticated = authenticatedSession(OWNER_EMAIL);
        owner.setAtivo(false);
        mvc.perform(get("/api/pdis").session(authenticated))
                .andExpect(status().isUnauthorized()).andExpect(header().doesNotExist(HttpHeaders.LOCATION));
        verify(usuarios).findById(OWNER);
        verifyNoInteractions(versoes);
    }

    @Test
    void meReturnsOnlyAuthenticatedIdentityWithNoStoreAndNoSensitiveFields() throws Exception {
        MockHttpSession authenticated = authenticatedSession(OWNER_EMAIL);
        MvcResult result = mvc.perform(get("/api/auth/me").session(authenticated))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(header().doesNotExist(HttpHeaders.LOCATION)).andReturn();
        JsonNode json = mapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertIdentityDto(json, owner);
        assertNoSensitiveResponse(result);
        verify(usuarios, atLeastOnce()).findById(OWNER);
        verify(usuarios, times(1)).findByEmailIgnoreCase(OWNER_EMAIL);
        verify(credenciais, times(1)).findById(OWNER);
        verifyNoInteractions(versoes);
    }

    @Test
    void meIgnoresIdentityAndProfileSuppliedByClient() throws Exception {
        MockHttpSession authenticated = authenticatedSession(OWNER_EMAIL);
        MvcResult result = mvc.perform(get("/api/auth/me").session(authenticated)
                        .param("id", OTHER.toString()).param("usuarioId", OTHER.toString())
                        .param("email", OTHER_EMAIL).param("perfil", "RH_ADMIN")
                        .header("X-Usuario-Id", OTHER.toString()))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store")).andReturn();
        assertIdentityDto(mapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8)), owner);
        assertNoSensitiveResponse(result);
        verify(usuarios, never()).findById(OTHER);
        verify(usuarios, never()).findByEmailIgnoreCase(OTHER_EMAIL);
        verifyNoInteractions(versoes);
    }

    @Test
    void meReflectsCurrentAccountDataWithoutChangingSessionIdentity() throws Exception {
        MockHttpSession authenticated = authenticatedSession(OWNER_EMAIL);
        Authentication originalAuthentication = authentication(authenticated);
        UsuarioPrincipal originalPrincipal = principal(authenticated);
        mvc.perform(get("/api/auth/me").session(authenticated)).andExpect(status().isOk())
                .andExpect(jsonPath("$.nome").value(owner.getNome()))
                .andExpect(jsonPath("$.email").value(OWNER_EMAIL))
                .andExpect(jsonPath("$.perfil").value("COLABORADOR"));

        // Replace the repository result, representing a subsequent change in the account.
        // No principal, authorities or other session attributes are modified by the test.
        Usuario updated = usuario(OWNER, "atualizado@talentsynk.invalid", PerfilUsuario.GESTOR);
        updated.setNome("Nome atualizado de teste");
        when(usuarios.findById(OWNER)).thenReturn(Optional.of(updated));
        MvcResult result = mvc.perform(get("/api/auth/me").session(authenticated))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store")).andReturn();
        assertIdentityDto(mapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8)), updated);
        assertNoSensitiveResponse(result);
        assertSame(originalAuthentication, authentication(authenticated));
        assertSame(originalPrincipal, principal(authenticated));
        assertEquals(owner.getNome(), originalPrincipal.getNome());
        assertEquals(OWNER_EMAIL, originalPrincipal.getEmail());
        assertEquals(PerfilUsuario.COLABORADOR, originalPrincipal.getPerfilAcesso());
        assertEquals(List.of("ROLE_COLABORADOR"), originalAuthentication.getAuthorities().stream()
                .map(authority -> authority.getAuthority()).toList());
        verify(usuarios, times(1)).findByEmailIgnoreCase(OWNER_EMAIL);
        verify(credenciais, times(1)).findById(OWNER);
        verifyNoInteractions(versoes);
    }

    @Test
    void anonymousMeIsRejectedWithoutUserLookup() throws Exception {
        assertMeUnauthorized(mvc.perform(get("/api/auth/me")));
        verifyNoInteractions(usuarios, credenciais, versoes);
    }

    @Test
    void preLoginCsrfSessionDoesNotAuthorizeMe() throws Exception {
        Token token = token(null);
        assertMeUnauthorized(mvc.perform(get("/api/auth/me").session(token.session())));
        verifyNoInteractions(usuarios, credenciais, versoes);
    }

    @Test
    void removedUserCannotReadMeWithExistingSession() throws Exception {
        MockHttpSession authenticated = authenticatedSession(OWNER_EMAIL);
        when(usuarios.findById(OWNER)).thenReturn(Optional.empty());
        assertMeUnauthorized(mvc.perform(get("/api/auth/me").session(authenticated)));
        verify(usuarios).findById(OWNER);
        verifyNoInteractions(versoes);
    }

    @Test
    void inactiveUserCannotReadMeWithExistingSession() throws Exception {
        MockHttpSession authenticated = authenticatedSession(OWNER_EMAIL);
        Usuario inactive = usuario(OWNER, OWNER_EMAIL, PerfilUsuario.COLABORADOR);
        inactive.setAtivo(false);
        when(usuarios.findById(OWNER)).thenReturn(Optional.of(inactive));
        assertMeUnauthorized(mvc.perform(get("/api/auth/me").session(authenticated)));
        verify(usuarios).findById(OWNER);
        verifyNoInteractions(versoes);
    }

    @Test
    void meDoesNotExposePersistenceFailureDetails() throws Exception {
        MockHttpSession authenticated = authenticatedSession(OWNER_EMAIL);
        String internalDetail = "detalhe-interno-sintetico";
        when(usuarios.findById(OWNER)).thenThrow(new InvalidDataAccessResourceUsageException(internalDetail));
        MvcResult result = mvc.perform(get("/api/auth/me").session(authenticated))
                .andExpect(status().isInternalServerError())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(header().doesNotExist(HttpHeaders.LOCATION))
                .andExpect(jsonPath("$.status").value(500))
                .andExpect(jsonPath("$.detail").value("Não foi possível consultar a identidade"))
                .andExpect(jsonPath("$.exception").doesNotExist())
                .andExpect(jsonPath("$.trace").doesNotExist()).andReturn();
        assertFalse(result.getResponse().getContentAsString(StandardCharsets.UTF_8).contains(internalDetail),
                "A resposta não deve incluir a mensagem interna da exceção");
        assertNoSensitiveResponse(result);
        verifyNoInteractions(versoes);
    }

    private void assertMeUnauthorized(ResultActions response) throws Exception {
        MvcResult result = response.andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(content().json("{\"message\":\"Autenticação inválida\"}", true))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(header().doesNotExist(HttpHeaders.LOCATION)).andReturn();
        assertNoSensitiveResponse(result);
    }

    private static void assertIdentityDto(JsonNode json, Usuario expected) {
        assertTrue(json.isObject());
        assertEquals(Set.of("id", "nome", "email", "perfil"), fieldNames(json));
        assertTrue(expected.getId().toString().equals(json.path("id").asText()),
                "A identidade deve corresponder ao UUID autenticado");
        assertEquals(expected.getNome(), json.path("nome").asText());
        assertEquals(expected.getEmail(), json.path("email").asText());
        assertEquals(expected.getPerfilAcesso().name(), json.path("perfil").asText());
        json.forEach(value -> assertTrue(value.isTextual(), "A resposta contém somente os quatro valores textuais"));
    }

    private void assertNoSensitiveResponse(MvcResult result) throws Exception {
        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertFalse(body.contains(PASSWORD), "A resposta não deve conter a senha");
        assertFalse(body.contains(PASSWORD_HASH), "A resposta não deve conter o hash");
        JsonNode json = mapper.readTree(body);
        for (String field : List.of("senha", "senhaHash", "senha_hash", "password", "passwordHash",
                "authorities", "adObjectId", "ad_object_id", "cargo", "gestor", "equipeAtual",
                "snapshot", "token", "sessionId")) {
            assertFalse(json.has(field), "A resposta não deve expor a propriedade " + field);
        }
    }

    private void assertLoginRejected(String email, String password) throws Exception {
        Token token = token(null);
        login(token, email, password).andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(content().json("{\"message\":\"Autenticação inválida\"}", true))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(header().doesNotExist(HttpHeaders.LOCATION))
                .andExpect(header().doesNotExist(HttpHeaders.WWW_AUTHENTICATE));
        assertNull(token.session().getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY));
        verifyNoInteractions(versoes);
    }

    private ResultActions login(Token token, String email, String password) throws Exception {
        return mvc.perform(post("/api/auth/login").session(token.session())
                .header(token.header(), token.value()).contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("email", email).param("password", password));
    }

    private MockHttpSession authenticatedSession(String email) throws Exception {
        Token token = token(null);
        MvcResult result = login(token, email, PASSWORD).andExpect(status().isNoContent())
                .andExpect(header().doesNotExist(HttpHeaders.LOCATION)).andReturn();
        MockHttpSession authenticated = session(result);
        principal(authenticated);
        return authenticated;
    }

    private Token token(MockHttpSession existing) throws Exception {
        MockHttpServletRequestBuilder request = get("/api/csrf");
        if (existing != null) request.session(existing);
        MvcResult result = mvc.perform(request).andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON)).andReturn();
        JsonNode json = mapper.readTree(result.getResponse().getContentAsString());
        assertEquals(Set.of("headerName", "parameterName", "token"), fieldNames(json));
        assertTrue(json.path("headerName").isTextual() && !json.path("headerName").asText().isBlank());
        assertTrue(json.path("parameterName").isTextual() && !json.path("parameterName").asText().isBlank());
        assertTrue(json.path("token").isTextual() && !json.path("token").asText().isBlank());
        CsrfToken stored = new HttpSessionCsrfTokenRepository().loadToken(result.getRequest());
        assertNotNull(stored, "O token deve estar armazenado na sessão pelo filtro real");
        return new Token(session(result), json.path("headerName").asText(), json.path("token").asText(),
                stored.getToken());
    }

    private static MockHttpSession session(MvcResult result) {
        return assertInstanceOf(MockHttpSession.class, result.getRequest().getSession(false));
    }

    private static Authentication authentication(MockHttpSession session) {
        SecurityContext security = assertInstanceOf(SecurityContext.class,
                session.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY));
        Authentication authentication = security.getAuthentication();
        assertNotNull(authentication);
        assertTrue(authentication.isAuthenticated());
        return authentication;
    }

    private static UsuarioPrincipal principal(MockHttpSession session) {
        return assertInstanceOf(UsuarioPrincipal.class, authentication(session).getPrincipal());
    }

    private static Usuario usuario(UUID id, String email, PerfilUsuario perfil) {
        Usuario usuario = new Usuario();
        ReflectionTestUtils.setField(usuario, "id", id);
        usuario.setNome("Pessoa fictícia de teste");
        usuario.setEmail(email);
        usuario.setPerfilAcesso(perfil);
        usuario.setAtivo(true);
        return usuario;
    }

    private static PdiVersao draft(Usuario titular, Long id, Long versaoId) {
        PDI pdi = new PDI();
        ReflectionTestUtils.setField(pdi, "id", id);
        pdi.setUsuario(titular);
        PdiVersao versao = new PdiVersao();
        ReflectionTestUtils.setField(versao, "id", versaoId);
        versao.setPdi(pdi);
        versao.setNumero(1);
        versao.setTitulo("Rascunho privado de teste");
        return versao;
    }

    private static void assertDraftDto(JsonNode json) {
        assertEquals(Set.of("id", "versaoId", "numeroVersao", "versaoVigenteId", "statusExecucao",
                "statusAprovacao", "titulo", "objetivo", "inicioPlanejado", "fimPlanejado",
                "criadoEm", "versaoCriadaEm"), fieldNames(json));
        json.forEach(value -> assertFalse(value.isObject() || value.isArray(), "Somente campos escalares do DTO"));
    }

    private static Set<String> fieldNames(JsonNode json) {
        Set<String> names = new HashSet<>();
        json.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private record Token(MockHttpSession session, String header, String value, String storedValue) {
        @Override public String toString() { return "Token [redacted]"; }
    }
}
