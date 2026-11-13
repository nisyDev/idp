package com.empresa.pdi.security;

import com.empresa.pdi.repository.UsuarioCredencialLocalRepository;
import com.empresa.pdi.repository.UsuarioRepository;
import jakarta.persistence.EntityManagerFactory;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.verifyNoInteractions;

/** Only the local configuration is loaded: no application, auto-configuration or web server. */
@ExtendWith(MockitoExtension.class)
class LocalAuthenticationConfigTest {
    private static final String MENSAGEM_PERFIS_INCOMPATIVEIS =
            "Os perfis auth-local e demo-local não podem ser ativados simultaneamente";

    @Mock UsuarioRepository usuarios;
    @Mock UsuarioCredencialLocalRepository credenciais;

    private ApplicationContextRunner runner;

    @BeforeEach
    void configurar() {
        runner = new ApplicationContextRunner()
                .withUserConfiguration(LocalAuthenticationConfig.class)
                .withBean(UsuarioRepository.class, () -> usuarios)
                .withBean(UsuarioCredencialLocalRepository.class, () -> credenciais);
    }

    @Test
    void authLocalRegistraCarregadorEEncoderBCryptComCusto12() {
        comPerfis("auth-local").run(context -> {
            assertThat(context).hasNotFailed()
                    .hasSingleBean(LocalAuthenticationConfig.class)
                    .hasSingleBean(LocalUserDetailsService.class)
                    .hasSingleBean(UserDetailsService.class)
                    .hasSingleBean(BCryptPasswordEncoder.class)
                    .hasSingleBean(PasswordEncoder.class);
            assertSame(context.getBean(LocalUserDetailsService.class),
                    context.getBean(UserDetailsService.class));
            BCryptPasswordEncoder encoder = context.getBean(BCryptPasswordEncoder.class);
            assertSame(encoder, context.getBean(PasswordEncoder.class));

            // Synthetic test input only; the resulting value is never logged or persisted.
            String entrada = "entrada-sintetica-do-teste-de-configuracao";
            String codificado = encoder.encode(entrada);
            assertTrue(codificado.matches("\\$2[aby]\\$12\\$[./A-Za-z0-9]{53}"),
                    "O encoder deve produzir BCrypt com custo 12");
            assertTrue(encoder.matches(entrada, codificado));
            assertFalse(encoder.matches("outra-entrada-sintetica", codificado));

            verificarSemPersistenciaReal(context);
        });
    }

    @Test
    void somenteDemoLocalNaoRegistraComponentesExclusivosDoAuthLocal() {
        // DemoAuthenticationConfig is intentionally not imported: no external accounts are read.
        comPerfis("demo-local").run(context -> {
            verificarComponentesLocaisAusentes(context);
            verificarSemPersistenciaReal(context);
        });
    }

    @Test
    void perfisCombinadosImpedemInicializacaoComMensagemSanitizada() {
        comPerfis("auth-local", "demo-local").run(context -> {
            assertThat(context).hasFailed();
            Throwable failure = context.getStartupFailure();
            assertNotNull(failure);
            while (failure.getCause() != null && failure.getCause() != failure) {
                failure = failure.getCause();
            }
            assertInstanceOf(IllegalStateException.class, failure);
            assertEquals(MENSAGEM_PERFIS_INCOMPATIVEIS, failure.getMessage());
            verifyNoInteractions(usuarios, credenciais);
        });
    }

    @Test
    void semAuthLocalNaoRegistraCarregadorOuEncoderLocal() {
        comPerfis().run(context -> {
            assertArrayEquals(new String[0], context.getEnvironment().getActiveProfiles());
            verificarComponentesLocaisAusentes(context);
            verificarSemPersistenciaReal(context);
        });
    }

    @Test
    void configuracaoLocalIsoladaNaoRegistraCadeiaHttpNemEndpoints() {
        comPerfis("auth-local").run(context -> {
            assertThat(context).hasNotFailed()
                    .hasSingleBean(LocalUserDetailsService.class)
                    .doesNotHaveBean(SecurityFilterChain.class)
                    .doesNotHaveBean(FilterChainProxy.class)
                    .doesNotHaveBean(AuthenticationManager.class)
                    .doesNotHaveBean(AuthenticationProvider.class)
                    .doesNotHaveBean(RequestMappingHandlerMapping.class);
            assertTrue(context.getBeansWithAnnotation(Controller.class).isEmpty());
            assertTrue(context.getBeansWithAnnotation(RestController.class).isEmpty());
            verificarSemPersistenciaReal(context);
        });
    }

    private ApplicationContextRunner comPerfis(String... perfis) {
        return runner.withInitializer(context -> {
            var environment = context.getEnvironment();
            // Change only this test context, never system properties or IntelliJ configuration.
            environment.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
            environment.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
            environment.setDefaultProfiles("default");
            environment.setActiveProfiles(perfis);
        });
    }

    private static void verificarComponentesLocaisAusentes(AssertableApplicationContext context) {
        assertThat(context).hasNotFailed()
                .doesNotHaveBean(LocalAuthenticationConfig.class)
                .doesNotHaveBean(LocalUserDetailsService.class)
                .doesNotHaveBean(UserDetailsService.class)
                .doesNotHaveBean(BCryptPasswordEncoder.class)
                .doesNotHaveBean(PasswordEncoder.class);
    }

    private void verificarSemPersistenciaReal(AssertableApplicationContext context) {
        assertThat(context).doesNotHaveBean(DataSource.class)
                .doesNotHaveBean(EntityManagerFactory.class);
        verifyNoInteractions(usuarios, credenciais);
    }
}
