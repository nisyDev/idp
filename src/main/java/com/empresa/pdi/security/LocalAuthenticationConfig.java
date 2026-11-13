package com.empresa.pdi.security;

import com.empresa.pdi.repository.UsuarioCredencialLocalRepository;
import com.empresa.pdi.repository.UsuarioRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/** Local authentication building blocks only; no HTTP login or security chain is configured here. */
@Configuration(proxyBeanMethods = false)
@Profile("auth-local")
public class LocalAuthenticationConfig {
    public LocalAuthenticationConfig(Environment environment) {
        if (environment.matchesProfiles("demo-local")) {
            throw new IllegalStateException("Os perfis auth-local e demo-local não podem ser ativados simultaneamente");
        }
    }

    @Bean
    LocalUserDetailsService localUserDetailsService(UsuarioRepository usuarios,
                                                   UsuarioCredencialLocalRepository credenciais) {
        return new LocalUserDetailsService(usuarios, credenciais);
    }

    @Bean
    BCryptPasswordEncoder localPasswordEncoder() {
        return new BCryptPasswordEncoder(12);
    }
}
