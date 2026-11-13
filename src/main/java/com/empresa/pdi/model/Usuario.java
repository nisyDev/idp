package com.empresa.pdi.model;

import com.empresa.pdi.enums.PerfilUsuario;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Check;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "usuario", uniqueConstraints = @UniqueConstraint(name = "uq_usuario_ad_object_id", columnNames = "ad_object_id"))
@Check(constraints = "perfil_acesso IN ('COLABORADOR', 'GESTOR', 'RH_ADMIN') AND (gestor_id IS NULL OR gestor_id <> id)")
@Getter
@Setter
@NoArgsConstructor
public class Usuario {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    @Setter(AccessLevel.NONE)
    private UUID id;

    @Column(name = "ad_object_id", columnDefinition = "text", updatable = false)
    @Setter(AccessLevel.NONE)
    private String adObjectId;

    @Column(name = "nome", nullable = false, columnDefinition = "text")
    private String nome;
    // V1 owns the unique expression index on LOWER(email).
    @Column(name = "email", nullable = false, columnDefinition = "text")
    private String email;

    @Enumerated(EnumType.STRING)
    @Column(name = "perfil_acesso", nullable = false, length = 20)
    private PerfilUsuario perfilAcesso;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "cargo_id", nullable = false, foreignKey = @ForeignKey(name = "fk_usuario_cargo"))
    private Cargo cargo;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "gestor_id", foreignKey = @ForeignKey(name = "fk_usuario_gestor"))
    private Usuario gestor;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "equipe_atual_id", nullable = false, foreignKey = @ForeignKey(name = "fk_usuario_equipe_atual"))
    private Equipe equipeAtual;

    @Column(name = "ativo", nullable = false)
    private boolean ativo = true;

    @Column(name = "data_criacao", nullable = false, updatable = false, columnDefinition = "timestamptz")
    @Setter(AccessLevel.NONE)
    private Instant dataCriacao;

    @Column(name = "data_atualizacao", nullable = false, columnDefinition = "timestamptz")
    @Setter(AccessLevel.NONE)
    private Instant dataAtualizacao;

    public Usuario(String adObjectId) {
        this.adObjectId = adObjectId;
    }

    @PrePersist
    private void registrarCriacao() {
        Instant agora = Instant.now();
        dataCriacao = agora;
        dataAtualizacao = agora;
    }

    @PreUpdate
    private void registrarAtualizacao() {
        dataAtualizacao = Instant.now();
    }
}
