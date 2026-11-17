package com.empresa.pdi.model;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import java.time.LocalDate;
import java.time.Instant;

@Entity
@Table(name = "acao_pdi_versao", uniqueConstraints = {
    @UniqueConstraint(name = "uq_acao_versao", columnNames = {"pdi_versao_id", "acao_pdi_id"}),
    @UniqueConstraint(name = "uq_acao_versao_pertencimento", columnNames = {"acao_pdi_id", "id"})
})
@Getter
@Setter
@NoArgsConstructor
public class AcaoPdiVersao {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Setter(AccessLevel.NONE)
    private Long id;

    // Scalars exclusively write shared FK columns. Associations are read-only.
    @Column(name = "pdi_id", nullable = false)
    @Setter(AccessLevel.NONE)
    private Long pdiId;

    @Column(name = "pdi_versao_id", nullable = false)
    @Setter(AccessLevel.NONE)
    private Long pdiVersaoId;

    @Column(name = "acao_pdi_id", nullable = false)
    @Setter(AccessLevel.NONE)
    private Long acaoPdiId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumns(value = {
        @JoinColumn(name = "pdi_id", referencedColumnName = "pdi_id", nullable = false, insertable = false, updatable = false),
        @JoinColumn(name = "pdi_versao_id", referencedColumnName = "id", nullable = false, insertable = false, updatable = false)
    }, foreignKey = @ForeignKey(name = "fk_acao_versao_plano"))
    @Setter(AccessLevel.NONE)
    private PdiVersao pdiVersao;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumns(value = {
        @JoinColumn(name = "pdi_id", referencedColumnName = "pdi_id", nullable = false, insertable = false, updatable = false),
        @JoinColumn(name = "acao_pdi_id", referencedColumnName = "id", nullable = false, insertable = false, updatable = false)
    }, foreignKey = @ForeignKey(name = "fk_acao_versao_identidade"))
    @Setter(AccessLevel.NONE)
    private AcaoPDI acaoPdi;

    @Column(name = "titulo", columnDefinition = "text")
    private String titulo;

    @Column(name = "objetivo", columnDefinition = "text")
    private String objetivo;

    @Column(name = "resultado_esperado", columnDefinition = "text")
    private String resultadoEsperado;

    @Column(name = "inicio_planejado")
    private LocalDate inicioPlanejado;

    @Column(name = "fim_planejado")
    private LocalDate fimPlanejado;

    @Column(name = "ordem", nullable = false)
    private Integer ordem = 0;

    // Removal belongs to this proposal's content, never to another version's identity.
    @Column(name = "removida_em", columnDefinition = "timestamptz")
    private Instant removidaEm;

    public void definirVinculos(PdiVersao versao, AcaoPDI acao) {
        if (versao == null || acao == null || versao.getId() == null || acao.getId() == null
                || versao.getPdi() == null || acao.getPdi() == null
                || versao.getPdi().getId() == null
                || !versao.getPdi().getId().equals(acao.getPdi().getId())) {
            throw new IllegalArgumentException("A versão e a ação devem estar persistidas e pertencer ao mesmo PDI");
        }
        pdiVersao = versao;
        acaoPdi = acao;
        pdiId = versao.getPdi().getId();
        pdiVersaoId = versao.getId();
        acaoPdiId = acao.getId();
    }
}
