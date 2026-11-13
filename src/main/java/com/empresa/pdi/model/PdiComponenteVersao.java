package com.empresa.pdi.model;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import java.math.BigDecimal;

@Entity
@Table(name = "pdi_componente_versao", uniqueConstraints =
    @UniqueConstraint(name = "uq_componente_versao", columnNames = {"acao_pdi_versao_id", "componente_id"}))
@Getter
@Setter
@NoArgsConstructor
public class PdiComponenteVersao {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Setter(AccessLevel.NONE)
    private Long id;

    // Scalars exclusively write shared FK columns; both associations are read-only.
    @Column(name = "acao_pdi_id", nullable = false)
    @Setter(AccessLevel.NONE)
    private Long acaoPdiId;

    @Column(name = "acao_pdi_versao_id", nullable = false)
    @Setter(AccessLevel.NONE)
    private Long acaoPdiVersaoId;

    @Column(name = "componente_id", nullable = false)
    @Setter(AccessLevel.NONE)
    private Long componenteId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumns(value = {
        @JoinColumn(name = "acao_pdi_id", referencedColumnName = "acao_pdi_id", nullable = false, insertable = false, updatable = false),
        @JoinColumn(name = "acao_pdi_versao_id", referencedColumnName = "id", nullable = false, insertable = false, updatable = false)
    }, foreignKey = @ForeignKey(name = "fk_componente_versao_acao"))
    @Setter(AccessLevel.NONE)
    private AcaoPdiVersao acaoPdiVersao;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumns(value = {
        @JoinColumn(name = "acao_pdi_id", referencedColumnName = "acao_pdi_id", nullable = false, insertable = false, updatable = false),
        @JoinColumn(name = "componente_id", referencedColumnName = "id", nullable = false, insertable = false, updatable = false)
    }, foreignKey = @ForeignKey(name = "fk_componente_versao_identidade"))
    @Setter(AccessLevel.NONE)
    private PdiComponente componente;

    @Column(name = "peso", precision = 5, scale = 2)
    private BigDecimal peso;

    @Column(name = "descricao", columnDefinition = "text")
    private String descricao;

    @Column(name = "criterio_aceite", columnDefinition = "text")
    private String criterioAceite;

    @Column(name = "forma_comprovacao", columnDefinition = "text")
    private String formaComprovacao;

    public void definirVinculos(AcaoPdiVersao versao, PdiComponente componente) {
        if (versao == null || componente == null || versao.getId() == null
                || componente.getId() == null || versao.getAcaoPdiId() == null
                || componente.getAcaoPdi() == null
                || !versao.getAcaoPdiId().equals(componente.getAcaoPdi().getId())) {
            throw new IllegalArgumentException("A versão e o componente devem ter IDs atribuídos e pertencer à mesma ação");
        }
        this.acaoPdiVersao = versao;
        this.componente = componente;
        this.acaoPdiId = versao.getAcaoPdiId();
        this.acaoPdiVersaoId = versao.getId();
        this.componenteId = componente.getId();
    }
}
