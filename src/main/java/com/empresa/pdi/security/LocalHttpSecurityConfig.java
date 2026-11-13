package com.empresa.pdi.security;

import jakarta.servlet.SessionTrackingMode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Set;
import org.springframework.boot.web.servlet.ServletContextInitializer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseCookie;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.DelegatingSecurityContextRepository;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.security.web.util.matcher.RegexRequestMatcher;

/** Session-based HTTP authentication only; the demo keeps its separate Basic chain. */
@Configuration(proxyBeanMethods = false)
@Profile("auth-local")
public class LocalHttpSecurityConfig {
    private static final long REMEMBERED_COOKIE_MAX_AGE = Duration.ofDays(7).toSeconds();

    @Bean
    AuthenticationManager localAuthenticationManager(LocalUserDetailsService usuarios,
                                                      BCryptPasswordEncoder localPasswordEncoder) {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider();
        provider.setUserDetailsService(usuarios);
        provider.setPasswordEncoder(localPasswordEncoder);
        provider.setHideUserNotFoundExceptions(true);
        ProviderManager manager = new ProviderManager(provider);
        manager.setEraseCredentialsAfterAuthentication(true);
        return manager;
    }

    @Bean
    SecurityFilterChain localSecurity(HttpSecurity http, AuthenticationManager manager) throws Exception {
        var contextRepository = new DelegatingSecurityContextRepository(
                new RequestAttributeSecurityContextRepository(), new HttpSessionSecurityContextRepository());
        return http.authenticationManager(manager)
                .securityContext(context -> context
                        .securityContextRepository(contextRepository).requireExplicitSave(true))
                .sessionManagement(sessions -> sessions
                        .sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED)
                        .sessionFixation(fixation -> fixation.changeSessionId()))
                .csrf(csrf -> csrf.csrfTokenRepository(new HttpSessionCsrfTokenRepository()))
                .requestCache(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(requests -> requests
                        .requestMatchers(HttpMethod.GET, "/api/csrf").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/auth/login", "/api/auth/logout").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/auth/me").authenticated()
                        .requestMatchers(HttpMethod.GET, "/api/cargos")
                        .hasAnyRole("COLABORADOR", "GESTOR", "RH_ADMIN")
                        .requestMatchers(HttpMethod.GET, "/api/pdis").authenticated()
                        .requestMatchers(new RegexRequestMatcher("^/api/pdis/[0-9]+$", "GET")).authenticated()
                        .requestMatchers(new RegexRequestMatcher("^/api/pdis/[0-9]+$", "PUT")).authenticated()
                        .requestMatchers(HttpMethod.POST, "/api/pdis").authenticated()
                        .requestMatchers(new RegexRequestMatcher("^/api/pdis/[0-9]+/acoes$", "POST")).authenticated()
                        .requestMatchers(new RegexRequestMatcher("^/api/pdis/[0-9]+/acoes(?:/[0-9]+)?$", "GET")).authenticated()
                        .requestMatchers(new RegexRequestMatcher("^/api/pdis/[0-9]+/acoes/[0-9]+$", "PUT")).authenticated()
                        .anyRequest().denyAll())
                .formLogin(login -> login
                        // Disable the generated HTML login page; only POST is authorized here.
                        .loginPage("/api/auth/login")
                        .loginProcessingUrl("/api/auth/login")
                        .usernameParameter("email")
                        .passwordParameter("password")
                        .successHandler((request, response, authentication) -> loginSucceeded(request, response))
                        .failureHandler((request, response, failure) -> unauthorized(response)))
                .logout(logout -> logout
                        .logoutRequestMatcher(new RegexRequestMatcher("^/api/auth/logout$", "POST"))
                        .invalidateHttpSession(true)
                        .clearAuthentication(true)
                        .logoutSuccessHandler((request, response, authentication) -> {
                            writeSessionCookie(request, response, "", 0);
                            noContent(response);
                        }))
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((request, response, failure) -> unauthorized(response))
                        .accessDeniedHandler((request, response, failure) ->
                                response.setStatus(HttpServletResponse.SC_FORBIDDEN)))
                .build();
    }

    @Bean
    ServletContextInitializer localSessionCookies() {
        return servletContext -> {
            var cookie = servletContext.getSessionCookieConfig();
            cookie.setHttpOnly(true);
            cookie.setAttribute("SameSite", "Lax");
            // Tomcat also marks this cookie Secure when request.isSecure() is true.
            // Keep any stronger external Secure setting; do not force it off for local HTTP.
            servletContext.setSessionTrackingModes(Set.of(SessionTrackingMode.COOKIE));
        };
    }

    private static void loginSucceeded(HttpServletRequest request, HttpServletResponse response) throws IOException {
        // Spring Security has already rotated the ID and saved its authenticated context.
        var session = request.getSession(false);
        if (session == null) {
            unauthorized(response);
            return;
        }

        long maxAge = -1; // No Max-Age/Expires: a normal browser-session cookie.
        if ("true".equals(request.getParameter("lembrarAcesso"))) {
            int configuredMaxAge = request.getServletContext().getSessionCookieConfig().getMaxAge();
            maxAge = configuredMaxAge > 0 ? configuredMaxAge : REMEMBERED_COOKIE_MAX_AGE;
        }
        // Only the cookie becomes persistent; the server's idle timeout is unchanged.
        writeSessionCookie(request, response, session.getId(), maxAge);
        noContent(response);
    }

    private static void writeSessionCookie(HttpServletRequest request, HttpServletResponse response,
                                           String value, long maxAge) {
        var config = request.getServletContext().getSessionCookieConfig();
        String name = config.getName() == null || config.getName().isBlank() ? "JSESSIONID" : config.getName();
        String path = config.getPath();
        if (path == null || path.isBlank()) path = request.getContextPath();
        if (path == null || path.isBlank()) path = "/";

        var cookie = ResponseCookie.from(name, value)
                .path(path)
                .httpOnly(true)
                .secure(request.isSecure() || config.isSecure())
                .sameSite("Lax")
                .maxAge(maxAge);
        if (config.getDomain() != null && !config.getDomain().isBlank()) cookie.domain(config.getDomain());

        // Replace the cookie emitted during session creation/fixation protection.
        // Preserve other Set-Cookie headers and avoid duplicate session cookies.
        var otherCookies = response.getHeaders(HttpHeaders.SET_COOKIE).stream()
                .filter(header -> !header.stripLeading().startsWith(name + "="))
                .toList();
        response.setHeader(HttpHeaders.SET_COOKIE, cookie.build().toString());
        otherCookies.forEach(header -> response.addHeader(HttpHeaders.SET_COOKIE, header));
    }

    private static void noContent(HttpServletResponse response) {
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        response.setStatus(HttpServletResponse.SC_NO_CONTENT);
    }

    private static void unauthorized(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write("{\"message\":\"Autenticação inválida\"}");
    }
}
