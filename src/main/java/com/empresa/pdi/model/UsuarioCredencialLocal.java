package com.empresa.pdi.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.MapsId;
import jakarta.persistence.OneToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Check;

/** Optional local credential. The absence of a row means no local password. */
@Entity
@Table(name = "usuario_credencial_local")
@Check(constraints = "char_length(senha_hash) = 60")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UsuarioCredencialLocal {
    @Id
    @Column(name = "usuario_id", nullable = false, updatable = false)
    private UUID usuarioId;

    @MapsId
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "usuario_id", nullable = false, updatable = false,
            foreignKey = @ForeignKey(name = "fk_usuario_credencial_local_usuario"))
    private Usuario usuario;

    @JsonIgnore
    @Column(name = "senha_hash", nullable = false, length = 60)
    private String senhaHash;

    @Column(name = "criada_em", nullable = false, updatable = false, columnDefinition = "timestamptz")
    private Instant criadaEm;

    @Column(name = "atualizada_em", nullable = false, columnDefinition = "timestamptz")
    private Instant atualizadaEm;

    public UsuarioCredencialLocal(Usuario usuario, String senhaHash) {
        if (usuario == null || usuario.getId() == null) {
            throw new IllegalArgumentException("O usuário da credencial deve possuir um identificador");
        }
        this.usuario = usuario;
        // Hibernate derives usuarioId from this association through @MapsId on persist.
        setSenhaHash(senhaHash);
    }

    /** Accepts an already generated hash; this entity never receives or hashes a password. */
    public void setSenhaHash(String senhaHash) {
        if (senhaHash == null || senhaHash.length() != 60) {
            throw new IllegalArgumentException("O hash BCrypt deve possuir 60 caracteres");
        }
        this.senhaHash = senhaHash;
    }

    @PrePersist
    private void registrarCriacao() {
        Instant agora = Instant.now();
        criadaEm = agora;
        atualizadaEm = agora;
    }

    @PreUpdate
    private void registrarAtualizacao() {
        atualizadaEm = Instant.now();
    }
}
