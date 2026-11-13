package com.empresa.pdi.controller;

import com.empresa.pdi.enums.NivelCargo;
import com.empresa.pdi.enums.PerfilUsuario;
import com.empresa.pdi.model.Cargo;
import com.empresa.pdi.model.Usuario;
import com.empresa.pdi.model.UsuarioCredencialLocal;
import com.empresa.pdi.repository.CargoRepository;
import com.empresa.pdi.repository.UsuarioCredencialLocalRepository;
import com.empresa.pdi.repository.UsuarioRepository;
import com.empresa.pdi.security.HttpSecurityConfig;
import com.empresa.pdi.security.LocalAuthenticationConfig;
import com.empresa.pdi.security.LocalHttpSecurityConfig;
import com.empresa.pdi.security.SecurityContextUsuarioAutenticadoProvider;
import com.empresa.pdi.security.UsuarioPrincipal;
import com.empresa.pdi.service.CargoConsultaService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManagerFactory;
import java.nio.charset.StandardCharsets;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
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
import org.springframework.dao.InvalidDataAccessResourceUsageException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
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

/** Real local login, filters, identity adapter and cargo service; repositories are mocks.
 * This verifies HTTP behavior and repository selection, without executing a database query.
 */
@WebMvcTest(controllers = {CargoController.class, CsrfController.class}, excludeAutoConfiguration = {
        DataSourceAutoConfiguration.class, HibernateJpaAutoConfiguration.class, FlywayAutoConfiguration.class})
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@Import({HttpSecurityConfig.class, LocalAuthenticationConfig.class, LocalHttpSecurityConfig.class,
        SecurityContextUsuarioAutenticadoProvider.class, CargoConsultaService.class})
@ActiveProfiles(value = "auth-local", inheritProfiles = false)
class CargoHttpTest {
    // Ephemeral synthetic credentials, never loaded from configuration or persisted.
    private static final String PASSWORD = UUID.randomUUID().toString();
    private static final String PASSWORD_HASH = new BCryptPasswordEncoder(12).encode(PASSWORD);
    private static final String INTERNAL_DETAIL = "detalhe-interno-catalogo-sintetico";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired ApplicationContext context;
    @MockBean UsuarioRepository usuarios;
    @MockBean UsuarioCredencialLocalRepository credenciais;
    @MockBean CargoRepository cargos;

    private final Map<PerfilUsuario, Usuario> accounts = new EnumMap<>(PerfilUsuario.class);
    private Cargo firstActive;
    private Cargo secondActive;
    private Cargo inactive;

    @BeforeEach
    void fixtures() {
        assertTrue(context.getBeansOfType(DataSource.class).isEmpty(), "O contexto não pode ter datasource");
        assertTrue(context.getBeansOfType(EntityManagerFactory.class).isEmpty(), "O contexto não pode carregar JPA");
        accounts.clear();
        for (PerfilUsuario perfil : PerfilUsuario.values()) {
            accounts.put(perfil, usuario(perfil));
        }
        when(usuarios.findByEmailIgnoreCase(anyString())).thenAnswer(call -> accounts.values().stream()
                .filter(usuario -> usuario.getEmail().equalsIgnoreCase(call.getArgument(0))).findFirst());
        when(usuarios.findById(any(UUID.class))).thenAnswer(call -> accounts.values().stream()
                .filter(usuario -> usuario.getId().equals(call.getArgument(0))).findFirst());
        when(credenciais.findById(any(UUID.class))).thenAnswer(call -> accounts.values().stream()
                .filter(usuario -> usuario.getId().equals(call.getArgument(0)))
                .map(usuario -> new UsuarioCredencialLocal(usuario, PASSWORD_HASH)).findFirst());
        firstActive = cargo("Analista de teste", "Descrição pública de teste", NivelCargo.PLENO, true);
        secondActive = cargo("Consultor de teste", null, NivelCargo.NAO_APLICAVEL, true);
        inactive = cargo("Cargo inativo de teste", "Descrição interna de teste", NivelCargo.SENIOR, false);
        // The distinct stubs detect use of the unrestricted repository method.
        // Active selection belongs to the repository; these mocks do not prove SQL filtering.
        when(cargos.findAll()).thenReturn(List.of(firstActive, inactive, secondActive));
        when(cargos.findByAtivoTrue()).thenReturn(List.of(firstActive, secondActive));
    }

    @AfterEach
    void onlyTheActiveCatalogQueryIsAllowed() {
        verify(cargos, never()).findAll();
        verify(cargos, never()).findByIdForUpdate(any(UUID.class));
        verify(cargos, never()).save(any());
        verify(cargos, never()).delete(any());
        // Each successful or failed catalog read explicitly verifies findByAtivoTrue.
        // Any other cargo repository operation, including writes or locks, fails here.
        verifyNoMoreInteractions(cargos);
        verify(usuarios, never()).save(any());
        verify(credenciais, never()).save(any());
    }

    @Test
    void collaboratorListsOnlyActiveCargoDtos() throws Exception {
        assertActiveCatalogFor(PerfilUsuario.COLABORADOR);
    }

    @Test
    void gestorListsOnlyActiveCargoDtos() throws Exception {
        assertActiveCatalogFor(PerfilUsuario.GESTOR);
    }

    @Test
    void rhAdminListsOnlyActiveCargoDtos() throws Exception {
        assertActiveCatalogFor(PerfilUsuario.RH_ADMIN);
    }

    @Test
    void anonymousAndPreLoginCsrfSessionsCannotReadCargos() throws Exception {
        assertUnauthorized(mvc.perform(get("/api/cargos")));
        Token beforeLogin = token(null);
        assertNull(beforeLogin.session().getAttribute(
                HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY));
        assertUnauthorized(mvc.perform(get("/api/cargos").session(beforeLogin.session())));
        verifyNoInteractions(usuarios, credenciais, cargos);
    }

    @Test
    void emptyActiveCatalogReturnsAnEmptyArray() throws Exception {
        MockHttpSession authenticated = authenticatedSession(PerfilUsuario.COLABORADOR);
        when(cargos.findByAtivoTrue()).thenReturn(List.of());
        MvcResult result = mvc.perform(get("/api/cargos").session(authenticated))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(content().json("[]", true))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store")).andReturn();
        assertNoSensitiveResponse(result);
        verify(cargos).findByAtivoTrue();
    }

    @Test
    void removedUserCannotReadCargosWithAnExistingSession() throws Exception {
        Usuario account = accounts.get(PerfilUsuario.COLABORADOR);
        MockHttpSession authenticated = authenticatedSession(PerfilUsuario.COLABORADOR);
        when(usuarios.findById(account.getId())).thenReturn(Optional.empty());
        assertUnauthorized(mvc.perform(get("/api/cargos").session(authenticated)));
        verify(usuarios).findById(account.getId());
        verifyNoInteractions(cargos);
    }

    @Test
    void deactivatedUserCannotReadCargosWithAnExistingSession() throws Exception {
        Usuario account = accounts.get(PerfilUsuario.RH_ADMIN);
        MockHttpSession authenticated = authenticatedSession(PerfilUsuario.RH_ADMIN);
        account.setAtivo(false);
        assertUnauthorized(mvc.perform(get("/api/cargos").session(authenticated)));
        verify(usuarios).findById(account.getId());
        verifyNoInteractions(cargos);
    }

    @Test
    void clientParametersCannotIncludeInactiveCargosOrSelectAnIdentity() throws Exception {
        Usuario other = accounts.get(PerfilUsuario.COLABORADOR);
        MockHttpSession authenticated = authenticatedSession(PerfilUsuario.RH_ADMIN);
        MvcResult result = mvc.perform(get("/api/cargos").session(authenticated)
                        .param("ativo", "false").param("incluirInativos", "true")
                        .param("includeInactive", "true").param("perfil", "RH_ADMIN")
                        .param("usuarioId", other.getId().toString())
                        .header("X-Usuario-Id", other.getId().toString()))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store")).andReturn();
        assertActiveCatalog(result);
        verify(usuarios).findById(accounts.get(PerfilUsuario.RH_ADMIN).getId());
        verify(usuarios, never()).findById(other.getId());
        verify(cargos).findByAtivoTrue();
    }

    @Test
    void mutationsStayForbiddenForEveryRoleWithValidPostLoginCsrf() throws Exception {
        for (PerfilUsuario perfil : PerfilUsuario.values()) {
            MockHttpSession authenticated = authenticatedSession(perfil);
            // Login rotates CSRF: obtain the current token after authentication.
            Token afterLogin = token(authenticated);
            for (HttpMethod method : List.of(HttpMethod.POST, HttpMethod.PUT, HttpMethod.PATCH, HttpMethod.DELETE)) {
                for (String path : List.of("/api/cargos", "/api/cargos/" + firstActive.getId())) {
                    mvc.perform(request(method, path).session(authenticated)
                                    .header(afterLogin.header(), afterLogin.value())
                                    .contentType(MediaType.APPLICATION_JSON).content("{}"))
                            .andExpect(status().isForbidden())
                            .andExpect(header().doesNotExist(HttpHeaders.LOCATION));
                }
            }
        }
        verifyNoInteractions(cargos);
    }

    @Test
    void detailAndOtherMethodsStayForbiddenForEveryRole() throws Exception {
        for (PerfilUsuario perfil : PerfilUsuario.values()) {
            MockHttpSession authenticated = authenticatedSession(perfil);
            mvc.perform(get("/api/cargos/" + firstActive.getId()).session(authenticated))
                    .andExpect(status().isForbidden());
            mvc.perform(get("/api/cargos/inativos").session(authenticated))
                    .andExpect(status().isForbidden());
            mvc.perform(head("/api/cargos").session(authenticated)).andExpect(status().isForbidden());
            mvc.perform(options("/api/cargos").session(authenticated)).andExpect(status().isForbidden());
        }
        verifyNoInteractions(cargos);
    }

    @Test
    void catalogFailureDoesNotExposePersistenceDetails() throws Exception {
        MockHttpSession authenticated = authenticatedSession(PerfilUsuario.GESTOR);
        when(cargos.findByAtivoTrue()).thenThrow(new InvalidDataAccessResourceUsageException(INTERNAL_DETAIL));
        assertGenericFailure(mvc.perform(get("/api/cargos").session(authenticated)));
        verify(cargos).findByAtivoTrue();
    }

    @Test
    void accountLookupFailureDoesNotExposeDetailsOrQueryCargos() throws Exception {
        Usuario account = accounts.get(PerfilUsuario.GESTOR);
        MockHttpSession authenticated = authenticatedSession(PerfilUsuario.GESTOR);
        when(usuarios.findById(account.getId()))
                .thenThrow(new InvalidDataAccessResourceUsageException(INTERNAL_DETAIL));
        assertGenericFailure(mvc.perform(get("/api/cargos").session(authenticated)));
        verify(usuarios).findById(account.getId());
        verifyNoInteractions(cargos);
    }

    private void assertActiveCatalogFor(PerfilUsuario perfil) throws Exception {
        Usuario account = accounts.get(perfil);
        MockHttpSession authenticated = authenticatedSession(perfil);
        MvcResult result = mvc.perform(get("/api/cargos").session(authenticated))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(header().doesNotExist(HttpHeaders.LOCATION)).andReturn();
        assertActiveCatalog(result);
        // Revalidation of the server-side identity must precede the cargo query.
        InOrder order = inOrder(usuarios, cargos);
        order.verify(usuarios).findById(account.getId());
        order.verify(cargos).findByAtivoTrue();
        verify(usuarios).findByEmailIgnoreCase(account.getEmail());
        verify(credenciais).findById(account.getId());
        assertEquals(perfil, principal(authenticated).getPerfilAcesso());
    }

    private void assertActiveCatalog(MvcResult result) throws Exception {
        JsonNode json = mapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertTrue(json.isArray());
        assertEquals(2, json.size());
        assertCargoDto(json.get(0), firstActive);
        assertCargoDto(json.get(1), secondActive);
        for (JsonNode cargo : json) {
            assertFalse(inactive.getId().toString().equals(cargo.path("id").asText()));
        }
        assertNoSensitiveResponse(result);
    }

    private static void assertCargoDto(JsonNode json, Cargo expected) {
        assertTrue(json.isObject());
        assertEquals(Set.of("id", "nome", "descricao", "nivel", "ativo"), fieldNames(json));
        assertEquals(expected.getId().toString(), json.path("id").asText());
        assertEquals(expected.getNome(), json.path("nome").asText());
        if (expected.getDescricao() == null) assertTrue(json.path("descricao").isNull());
        else assertEquals(expected.getDescricao(), json.path("descricao").asText());
        assertEquals(expected.getNivel().name(), json.path("nivel").asText());
        assertTrue(json.path("ativo").isBoolean());
        assertTrue(json.path("ativo").asBoolean());
        json.forEach(value -> assertFalse(value.isObject() || value.isArray(), "Somente campos escalares do DTO"));
    }

    private void assertUnauthorized(ResultActions response) throws Exception {
        MvcResult result = response.andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(content().json("{\"message\":\"Autenticação inválida\"}", true))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(header().doesNotExist(HttpHeaders.LOCATION))
                .andExpect(header().doesNotExist(HttpHeaders.WWW_AUTHENTICATE)).andReturn();
        assertNoSensitiveResponse(result);
    }

    private void assertGenericFailure(ResultActions response) throws Exception {
        MvcResult result = response.andExpect(status().isInternalServerError())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(header().doesNotExist(HttpHeaders.LOCATION))
                .andExpect(jsonPath("$.status").value(500))
                .andExpect(jsonPath("$.detail").value("Não foi possível consultar os cargos"))
                .andExpect(jsonPath("$.exception").doesNotExist())
                .andExpect(jsonPath("$.trace").doesNotExist()).andReturn();
        assertNoSensitiveResponse(result);
    }

    private void assertNoSensitiveResponse(MvcResult result) throws Exception {
        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertFalse(body.contains(PASSWORD), "A resposta não deve conter a senha");
        assertFalse(body.contains(PASSWORD_HASH), "A resposta não deve conter o hash");
        assertFalse(body.contains(INTERNAL_DETAIL), "A resposta não deve conter detalhes internos");
        assertFalse(body.contains(inactive.getNome()), "A resposta não deve conter o cargo inativo");
        assertFalse(body.contains(inactive.getDescricao()), "A resposta não deve conter a descrição inativa");
        JsonNode json = mapper.readTree(body);
        for (String field : List.of("senha", "senhaHash", "senha_hash", "password", "passwordHash", "email",
                "authorities", "adObjectId", "ad_object_id", "gestor", "equipeAtual", "usuarios", "usuario",
                "snapshot", "token", "sessionId", "exception", "trace", "stackTrace")) {
            assertTrue(json.findValues(field).isEmpty(), "A resposta não deve expor a propriedade " + field);
        }
    }

    private MockHttpSession authenticatedSession(PerfilUsuario perfil) throws Exception {
        Usuario account = accounts.get(perfil);
        Token beforeLogin = token(null);
        MvcResult result = mvc.perform(post("/api/auth/login").session(beforeLogin.session())
                        .header(beforeLogin.header(), beforeLogin.value())
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("email", account.getEmail()).param("password", PASSWORD))
                .andExpect(status().isNoContent())
                .andExpect(header().doesNotExist(HttpHeaders.LOCATION)).andReturn();
        MockHttpSession authenticated = session(result);
        assertEquals(account.getId(), principal(authenticated).getUsuarioId());
        return authenticated;
    }

    private Token token(MockHttpSession existing) throws Exception {
        MockHttpServletRequestBuilder request = get("/api/csrf");
        if (existing != null) request.session(existing);
        MvcResult result = mvc.perform(request).andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON)).andReturn();
        JsonNode json = mapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertEquals(Set.of("headerName", "parameterName", "token"), fieldNames(json));
        CsrfToken stored = new HttpSessionCsrfTokenRepository().loadToken(result.getRequest());
        assertNotNull(stored, "O token deve estar armazenado na sessão pelo filtro real");
        assertFalse(json.path("headerName").asText().isBlank());
        assertFalse(json.path("token").asText().isBlank());
        return new Token(session(result), json.path("headerName").asText(), json.path("token").asText());
    }

    private static MockHttpSession session(MvcResult result) {
        return assertInstanceOf(MockHttpSession.class, result.getRequest().getSession(false));
    }

    private static UsuarioPrincipal principal(MockHttpSession session) {
        SecurityContext security = assertInstanceOf(SecurityContext.class,
                session.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY));
        Authentication authentication = security.getAuthentication();
        assertNotNull(authentication);
        assertTrue(authentication.isAuthenticated());
        return assertInstanceOf(UsuarioPrincipal.class, authentication.getPrincipal());
    }

    private static Usuario usuario(PerfilUsuario perfil) {
        Usuario usuario = new Usuario();
        ReflectionTestUtils.setField(usuario, "id", UUID.randomUUID());
        usuario.setNome("Pessoa fictícia " + perfil.name());
        usuario.setEmail(perfil.name().toLowerCase(java.util.Locale.ROOT) + "@talentsynk.invalid");
        usuario.setPerfilAcesso(perfil);
        usuario.setAtivo(true);
        return usuario;
    }

    private static Cargo cargo(String nome, String descricao, NivelCargo nivel, boolean ativo) {
        Cargo cargo = new Cargo();
        ReflectionTestUtils.setField(cargo, "id", UUID.randomUUID());
        cargo.setNome(nome);
        cargo.setDescricao(descricao);
        cargo.setNivel(nivel);
        cargo.setAtivo(ativo);
        return cargo;
    }

    private static Set<String> fieldNames(JsonNode json) {
        Set<String> names = new HashSet<>();
        json.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private record Token(MockHttpSession session, String header, String value) {
        @Override public String toString() { return "Token [redacted]"; }
    }
}
