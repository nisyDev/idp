package com.empresa.pdi.security;

import com.empresa.pdi.repository.UsuarioRepository;
import com.empresa.pdi.model.Usuario;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.Optional;

@Configuration(proxyBeanMethods = false)
@Profile("demo-local")
public class DemoAuthenticationConfig {
    @Bean
    AuthenticationManager demoAuthenticationManager(Environment environment, UsuarioRepository usuarios) {
        Map<String, Account> accounts = readAccounts(environment);
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider();
        provider.setPasswordEncoder(new BCryptPasswordEncoder());
        provider.setHideUserNotFoundExceptions(true);
        provider.setUserDetailsService(login -> {
            Account account = accounts.get(login);
            boolean userActive = false;
            if (account != null) {
                Optional<Usuario> user = usuarios.findById(account.usuarioId());
                userActive = user.filter(usuario -> usuario.isAtivo()).isPresent();
            }
            if (account == null || !userActive) {
                throw new UsernameNotFoundException("Autenticação inválida");
            }
            return new DemoUsuarioPrincipal(login, account.hash(), account.usuarioId());
        });
        return new ProviderManager(provider);
    }

    private static Map<String, Account> readAccounts(Environment environment) {
        try {
            String json = environment.getProperty("TALENTSYNK_DEMO_ACCOUNTS");
            if (json == null || json.isBlank()) throw new IllegalArgumentException();
            var root = new ObjectMapper().readTree(json);
            if (!root.isArray() || root.isEmpty()) throw new IllegalArgumentException();
            Map<String, Account> accounts = new HashMap<>();
            Set<UUID> identities = new HashSet<>();
            for (var entry : root) {
                if (!entry.isObject() || entry.size() != 3
                        || !entry.path("login").isTextual() || !entry.path("passwordHash").isTextual()
                        || !entry.path("usuarioId").isTextual()) throw new IllegalArgumentException();
                String login = entry.path("login").asText();
                String hash = entry.path("passwordHash").asText();
                String rawId = entry.path("usuarioId").asText();
                UUID id = UUID.fromString(rawId);
                if (login.isBlank() || !login.equals(login.trim()) || login.contains(":" )
                        || !id.toString().equalsIgnoreCase(rawId)
                        || !hash.matches("\\$2[aby]\\$(10|11|12|13|14)\\$[./A-Za-z0-9]{53}")
                        || !identities.add(id)
                        || accounts.putIfAbsent(login, new Account(hash, id)) != null) {
                    throw new IllegalArgumentException();
                }
            }
            return Map.copyOf(accounts);
        } catch (Exception ignored) {
            // Never include raw JSON, hash, UUID parsing input or original cause.
            throw new IllegalStateException("Configuração de autenticação demo ausente ou inválida");
        }
    }

    private record Account(String hash, UUID usuarioId) {
        @Override public String toString() { return "DemoAccount [redacted]"; }
    }
}
