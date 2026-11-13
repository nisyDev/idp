package com.empresa.pdi.model;

import com.empresa.pdi.enums.StatusExecucaoPdi;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import java.time.Instant;

@Entity
@Table(name = "pdi")
@Getter
@Setter
@NoArgsConstructor
public class PDI {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Setter(AccessLevel.NONE)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "usuario_id", nullable = false)
    private Usuario usuario;

    // Single writable mapping for the nullable part of the composite FK.
    @Column(name = "versao_vigente_id")
    @Setter(AccessLevel.NONE)
    private Long versaoVigenteId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumns(value = {
        @JoinColumn(name = "id", referencedColumnName = "pdi_id", insertable = false, updatable = false),
        @JoinColumn(name = "versao_vigente_id", referencedColumnName = "id", insertable = false, updatable = false)
    }, foreignKey = @ForeignKey(name = "fk_pdi_versao_vigente"))
    @Setter(AccessLevel.NONE)
    private PdiVersao versaoVigente;

    @Enumerated(EnumType.STRING)
    @Column(name = "status_execucao", nullable = false, length = 32)
    private StatusExecucaoPdi statusExecucao = StatusExecucaoPdi.NAO_INICIADO;

    @Column(name = "criado_em", nullable = false, updatable = false, columnDefinition = "timestamptz")
    @Setter(AccessLevel.NONE)
    private Instant criadoEm;

    @Column(name = "iniciado_em", columnDefinition = "timestamptz")
    private Instant iniciadoEm;

    @Column(name = "concluido_em", columnDefinition = "timestamptz")
    private Instant concluidoEm;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "concluido_por_id")
    private Usuario concluidoPor;

    @Version
    @Column(name = "lock_version", nullable = false)
    @Setter(AccessLevel.NONE)
    private Long lockVersion;

    public void setVersaoVigente(PdiVersao versao) {
        if (versao != null && (id == null || versao.getId() == null
                || versao.getPdi() == null || !id.equals(versao.getPdi().getId()))) {
            throw new IllegalArgumentException("A versão vigente deve estar persistida e pertencer ao mesmo PDI");
        }
        versaoVigente = versao;
        versaoVigenteId = versao == null ? null : versao.getId();
    }

    @PrePersist
    private void registrarCriacao() {
        criadoEm = Instant.now();
    }
}
