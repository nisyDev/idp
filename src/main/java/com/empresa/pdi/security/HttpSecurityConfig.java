package com.empresa.pdi.security;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.www.BasicAuthenticationEntryPoint;
import org.springframework.http.HttpMethod;
import org.springframework.security.web.util.matcher.RegexRequestMatcher;

@Configuration(proxyBeanMethods = false)
public class HttpSecurityConfig {
    @Bean
    @Profile("!demo-local & !auth-local")
    AuthenticationManager disabledAuthenticationManager() {
        // Explicit manager also prevents Boot from creating a default demo user/password.
        return authentication -> { throw new AuthenticationServiceException("Autenticação indisponível"); };
    }

    @Bean
    @Profile("!demo-local & !auth-local")
    SecurityFilterChain closedSecurity(HttpSecurity http) throws Exception {
        return common(http)
                .authorizeHttpRequests(requests -> requests.anyRequest().denyAll())
                .httpBasic(AbstractHttpConfigurer::disable)
                .exceptionHandling(errors -> errors.authenticationEntryPoint((request, response, failure) ->
                        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED)))
                .build();
    }

    @Bean
    @Profile("demo-local")
    SecurityFilterChain demoSecurity(HttpSecurity http, AuthenticationManager manager) throws Exception {
        BasicAuthenticationEntryPoint entryPoint = new BasicAuthenticationEntryPoint() {
            @Override
            public void commence(jakarta.servlet.http.HttpServletRequest request,
                                 HttpServletResponse response,
                                 org.springframework.security.core.AuthenticationException exception) {
                response.setHeader("WWW-Authenticate", "Basic realm=\"TalentSynk local demo\"");
                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            }
        };
        entryPoint.setRealmName("TalentSynk local demo");
        entryPoint.afterPropertiesSet();
        return common(http).authenticationManager(manager)
                .authorizeHttpRequests(requests -> requests
                        .requestMatchers(HttpMethod.GET, "/api/pdis", "/api/csrf").authenticated()
                        .requestMatchers(new RegexRequestMatcher("^/api/pdis/[0-9]+$", "GET")).authenticated()
                        .requestMatchers(new RegexRequestMatcher("^/api/pdis/[0-9]+$", "PUT")).authenticated()
                        .requestMatchers(HttpMethod.POST, "/api/pdis").authenticated()
                        .requestMatchers(new RegexRequestMatcher("^/api/pdis/[0-9]+/acoes$", "POST")).authenticated()
                        .requestMatchers(new RegexRequestMatcher("^/api/pdis/[0-9]+/acoes(?:/[0-9]+)?$", "GET")).authenticated()
                        .anyRequest().denyAll())
                .httpBasic(basic -> basic.authenticationEntryPoint(entryPoint))
                .exceptionHandling(errors -> errors.authenticationEntryPoint(entryPoint)).build();
    }

    private static HttpSecurity common(HttpSecurity http) throws Exception {
        // CSRF remains enabled. The token endpoint uses its default HTTP-session repository;
        // clients must keep the session cookie as well as sending Basic credentials and token.
        return http.formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .sessionManagement(sessions -> sessions.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(errors -> errors.accessDeniedHandler((request, response, failure) ->
                        response.setStatus(HttpServletResponse.SC_FORBIDDEN)));
    }
}
