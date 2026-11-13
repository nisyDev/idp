package com.empresa.pdi.security;

import com.empresa.pdi.enums.PerfilUsuario;
import com.empresa.pdi.model.Usuario;
import com.fasterxml.jackson.annotation.JsonIgnore;
import java.io.Serial;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.security.core.CredentialsContainer;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

/** Internal identity copied from Usuario, independent of the authentication provider. */
public final class UsuarioPrincipal implements UserDetails, CredentialsContainer {
    @Serial
    private static final long serialVersionUID = 1L;

    private final UUID usuarioId;
    private final String nome;
    private final String email;
    private final PerfilUsuario perfilAcesso;
    private final boolean ativo;
    private final List<GrantedAuthority> authorities;

    @JsonIgnore
    private transient String senhaHash;

    /** For a future externally verified identity; this constructor does not authenticate anyone. */
    public UsuarioPrincipal(Usuario usuario) {
        Objects.requireNonNull(usuario, "Usuário obrigatório");
        usuarioId = Objects.requireNonNull(usuario.getId(), "Identificador do usuário obrigatório");
        nome = Objects.requireNonNull(usuario.getNome(), "Nome do usuário obrigatório");
        email = Objects.requireNonNull(usuario.getEmail(), "E-mail do usuário obrigatório");
        perfilAcesso = Objects.requireNonNull(usuario.getPerfilAcesso(), "Perfil do usuário obrigatório");
        ativo = usuario.isAtivo();
        authorities = List.of(new SimpleGrantedAuthority("ROLE_" + perfilAcesso.name()));
    }

    /** The encoded local credential is available only until credentials are erased. */
    public UsuarioPrincipal(Usuario usuario, String senhaHash) {
        this(usuario);
        if (senhaHash == null || senhaHash.isBlank()) {
            throw new IllegalArgumentException("Credencial local inválida");
        }
        this.senhaHash = senhaHash;
    }

    public UUID getUsuarioId() {
        return usuarioId;
    }

    public String getNome() {
        return nome;
    }

    public String getEmail() {
        return email;
    }

    public PerfilUsuario getPerfilAcesso() {
        return perfilAcesso;
    }

    @Override
    public List<GrantedAuthority> getAuthorities() {
        return authorities;
    }

    @Override
    @JsonIgnore
    public String getPassword() {
        return senhaHash;
    }

    @Override
    public String getUsername() {
        return email;
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return true;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    @Override
    public boolean isEnabled() {
        return ativo;
    }

    @Override
    public void eraseCredentials() {
        senhaHash = null;
    }

    @Override
    public String toString() {
        return "UsuarioPrincipal [redacted]";
    }
}
