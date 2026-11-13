package com.empresa.pdi.model;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import java.util.UUID;

@Entity
@Table(name = "equipe", uniqueConstraints = @UniqueConstraint(name = "uq_equipe_lider", columnNames = "lider_id"))
@Getter
@Setter
@NoArgsConstructor
public class Equipe {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    @Setter(AccessLevel.NONE)
    private UUID id;

    @Column(name = "nome", nullable = false, columnDefinition = "text")
    private String nome;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "lider_id", foreignKey = @ForeignKey(name = "fk_equipe_lider"))
    private Usuario lider;

    @Column(name = "descricao", columnDefinition = "text")
    private String descricao;

    @Column(name = "ativo", nullable = false)
    private boolean ativo = true;
}
