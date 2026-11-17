package com.empresa.pdi.controller;

import com.empresa.pdi.repository.AcaoPDIRepository;
import com.empresa.pdi.repository.AcaoPdiVersaoRepository;
import com.empresa.pdi.repository.CargoRepository;
import com.empresa.pdi.repository.EquipeRepository;
import com.empresa.pdi.repository.HistoricoEquipeRepository;
import com.empresa.pdi.repository.PDIRepository;
import com.empresa.pdi.repository.PdiEventoRepository;
import com.empresa.pdi.repository.PdiVersaoRepository;
import com.empresa.pdi.repository.ReuniaoRepository;
import com.empresa.pdi.repository.UsuarioCredencialLocalRepository;
import com.empresa.pdi.repository.UsuarioRepository;
import com.empresa.pdi.security.DemoAuthenticationConfig;
import com.empresa.pdi.security.HttpSecurityConfig;
import com.empresa.pdi.security.LocalAuthenticationConfig;
import com.empresa.pdi.security.LocalHttpSecurityConfig;
import com.empresa.pdi.security.SecurityContextUsuarioAutenticadoProvider;
import com.empresa.pdi.service.AcaoPdiService;
import com.empresa.pdi.service.CargoConsultaService;
import com.empresa.pdi.service.IdentidadeAutenticadaService;
import com.empresa.pdi.service.PdiService;
import com.empresa.pdi.service.PdiAcessoService;
import com.empresa.pdi.service.UsuarioService;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.EntityManager;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Real closed HTTP chain, component discovery and no active local authentication profile. */
// No controllers selector: production controllers/advice are discovered with their @Profile.
@WebMvcTest(excludeAutoConfiguration = {
        DataSourceAutoConfiguration.class, HibernateJpaAutoConfiguration.class, FlywayAutoConfiguration.class
}, properties = {
        // Use an optional, absent classpath resource instead of external/application configuration.
        "spring.config.location=optional:classpath:/talentsynk-cargo-profile-isolation.properties",
        "spring.config.additional-location=",
        "spring.config.import=",
        "spring.profiles.include=",
        "spring.profiles.active=",
        "spring.profiles.default=default",
        "spring.flyway.enabled=false",
        "spring.sql.init.mode=never",
        "spring.jpa.hibernate.ddl-auto=none"
})
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@Import({HttpSecurityConfig.class, DemoAuthenticationConfig.class,
        LocalAuthenticationConfig.class, LocalHttpSecurityConfig.class,
        SecurityContextUsuarioAutenticadoProvider.class,
        PdiService.class, PdiAcessoService.class, AcaoPdiService.class, UsuarioService.class,
        CargoConsultaService.class, IdentidadeAutenticadaService.class})
@ActiveProfiles(inheritProfiles = false)
@ContextConfiguration(initializers = CargoDefaultProfileHttpTest.DefaultOnlyEnvironment.class)
class CargoDefaultProfileHttpTest {
    @Autowired MockMvc mvc;
    @Autowired ApplicationContext context;
    @Autowired FilterChainProxy securityFilters;
    @Autowired RequestMappingHandlerMapping mappings;

    // Only persistence dependencies are mocks; controllers, services and security are real.
    @MockBean UsuarioRepository usuarios;
    @MockBean EntityManager entityManager;
    @MockBean UsuarioCredencialLocalRepository credenciais;
    @MockBean CargoRepository cargos;
    @MockBean EquipeRepository equipes;
    @MockBean HistoricoEquipeRepository historicos;
    @MockBean PDIRepository pdis;
    @MockBean PdiVersaoRepository versoes;
    @MockBean PdiEventoRepository eventos;
    @MockBean AcaoPDIRepository acoes;
    @MockBean AcaoPdiVersaoRepository acoesVersoes;
    @MockBean ReuniaoRepository reunioes;

    @BeforeEach
    void realClosedChainAndProfileIsolation() {
        assertArrayEquals(new String[0], context.getEnvironment().getActiveProfiles());
        assertArrayEquals(new String[]{"default"}, context.getEnvironment().getDefaultProfiles());
        assertEquals(Set.of("closedSecurity"), context.getBeansOfType(SecurityFilterChain.class).keySet());
        assertEquals(Set.of("disabledAuthenticationManager"),
                context.getBeansOfType(AuthenticationManager.class).keySet());
        assertFalse(context.containsBean("localSecurity"));
        assertFalse(context.containsBean("demoSecurity"));
        assertFalse(context.containsBean("localAuthenticationManager"));
        assertEquals(1, securityFilters.getFilterChains().size());
        SecurityFilterChain chain = context.getBean("closedSecurity", SecurityFilterChain.class);
        assertSame(chain, securityFilters.getFilterChains().get(0));
        assertTrue(chain.getFilters().stream().anyMatch(AuthorizationFilter.class::isInstance));
        assertTrue(chain.getFilters().stream().noneMatch(BasicAuthenticationFilter.class::isInstance));
        assertTrue(context.getBeansOfType(CargoController.class).isEmpty(),
                "CargoController deve respeitar @Profile(auth-local)");
        assertTrue(context.getBeansOfType(CargoConsultaService.class).isEmpty(),
                "CargoConsultaService importado deve respeitar @Profile(auth-local)");
        assertTrue(context.getBeansOfType(DataSource.class).isEmpty(), "DataSource não pode ser criado");
        assertTrue(context.getBeansOfType(EntityManagerFactory.class).isEmpty(), "JPA não pode iniciar");
        assertTrue(context.getBeansOfType(Flyway.class).isEmpty(), "Flyway não pode ser criado");
        assertTrue(mockingDetails(cargos).isMock());
        assertTrue(mockingDetails(usuarios).isMock());

        // Discovery must actually run; an empty controller set would be a false isolation proof.
        assertEquals(1, context.getBeansOfType(PDIController.class).size());
        assertEquals(1, context.getBeansOfType(UsuarioController.class).size());
        assertTrue(mappings.getHandlerMethods().values().stream()
                .anyMatch(handler -> PDIController.class.isAssignableFrom(handler.getBeanType())));
        assertTrue(mappings.getHandlerMethods().values().stream()
                .noneMatch(handler -> CargoController.class.isAssignableFrom(handler.getBeanType())));
        assertTrue(mappings.getHandlerMethods().keySet().stream()
                .flatMap(mapping -> mapping.getPatternValues().stream())
                .noneMatch(path -> path.equals("/api/cargos") || path.startsWith("/api/cargos/")),
                "Nenhum mapeamento de cargos pode existir fora de auth-local");
        assertTrue(context.getBeansOfType(CsrfController.class).isEmpty());
    }

    @AfterEach
    void noPersistenceIsAccessed() {
        verifyNoInteractions(usuarios, credenciais, cargos, equipes, historicos, pdis,
                versoes, eventos, acoes, acoesVersoes, reunioes);
    }

    @Test
    void anonymousCatalogRequestIs401InDefaultProfile() throws Exception {
        mvc.perform(get("/api/cargos"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().doesNotExist(HttpHeaders.WWW_AUTHENTICATE));
    }

    @Test
    void basicAttemptCannotAuthenticateOrReadCatalogInDefaultProfile() throws Exception {
        // No account is configured in this profile; these are synthetic, unregistered credentials.
        String basic = "Basic " + Base64.getEncoder().encodeToString(
                ("catalogo-nao-configurado:" + UUID.randomUUID()).getBytes(StandardCharsets.UTF_8));
        mvc.perform(get("/api/cargos").header(HttpHeaders.AUTHORIZATION, basic))
                .andExpect(status().isUnauthorized())
                .andExpect(header().doesNotExist(HttpHeaders.WWW_AUTHENTICATE));
    }

    static final class DefaultOnlyEnvironment implements ApplicationContextInitializer<ConfigurableApplicationContext> {
        @Override
        public void initialize(ConfigurableApplicationContext applicationContext) {
            var environment = applicationContext.getEnvironment();
            // This affects only this context, not the running auth-local application.
            environment.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
            environment.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
            environment.setDefaultProfiles("default");
            environment.setActiveProfiles();
        }
    }
}
