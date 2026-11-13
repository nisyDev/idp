package com.empresa.pdi.model;

import com.empresa.pdi.enums.StatusAprovacaoPdi;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(name = "pdi_versao", uniqueConstraints = {
    @UniqueConstraint(name = "uq_pdi_versao_numero", columnNames = {"pdi_id", "numero"}),
    @UniqueConstraint(name = "uq_pdi_versao_pertencimento", columnNames = {"pdi_id", "id"})
})
@Getter
@Setter
@NoArgsConstructor
public class PdiVersao {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Setter(AccessLevel.NONE)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "pdi_id", nullable = false)
    private PDI pdi;

    @Column(name = "numero", nullable = false)
    private Integer numero;

    @Column(name = "titulo", columnDefinition = "text")
    private String titulo;

    @Column(name = "objetivo", columnDefinition = "text")
    private String objetivo;

    @Column(name = "inicio_planejado")
    private LocalDate inicioPlanejado;

    @Column(name = "fim_planejado")
    private LocalDate fimPlanejado;

    @Enumerated(EnumType.STRING)
    @Column(name = "status_aprovacao", nullable = false, length = 32)
    private StatusAprovacaoPdi statusAprovacao = StatusAprovacaoPdi.RASCUNHO;

    @Column(name = "criada_em", nullable = false, updatable = false, columnDefinition = "timestamptz")
    @Setter(AccessLevel.NONE)
    private Instant criadaEm;

    @Column(name = "submetida_em", columnDefinition = "timestamptz")
    private Instant submetidaEm;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "gestor_na_submissao_id")
    private Usuario gestorNaSubmissao;

    @Column(name = "decidida_em", columnDefinition = "timestamptz")
    private Instant decididaEm;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "decidido_por_id")
    private Usuario decididoPor;

    @Column(name = "justificativa_decisao", columnDefinition = "text")
    private String justificativaDecisao;

    @Column(name = "retirada_em", columnDefinition = "timestamptz")
    private Instant retiradaEm;

    @PrePersist
    private void registrarCriacao() {
        criadaEm = Instant.now();
    }
}
