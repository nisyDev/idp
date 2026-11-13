package com.empresa.pdi.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.Instant;

/** Historical record. ORM immutability does not prevent deletion or direct SQL writes. */
@Entity
@Table(name = "pdi_evento")
@Immutable
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PdiEvento {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Scalars are the sole writers of columns shared by composite associations.
    @Column(name = "pdi_id", nullable = false, updatable = false)
    private Long pdiId;

    @Column(name = "pdi_versao_id", nullable = false, updatable = false)
    private Long pdiVersaoId;

    @Column(name = "acao_pdi_id", updatable = false)
    private Long acaoPdiId;

    @Column(name = "componente_id", updatable = false)
    private Long componenteId;

    // Navigation derived from the mandatory version FK; V2 has no separate pdi FK.
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "pdi_id", nullable = false, insertable = false, updatable = false,
            foreignKey = @ForeignKey(ConstraintMode.NO_CONSTRAINT))
    private PDI pdi;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumns(value = {
        @JoinColumn(name = "pdi_id", referencedColumnName = "pdi_id", nullable = false, insertable = false, updatable = false),
        @JoinColumn(name = "pdi_versao_id", referencedColumnName = "id", nullable = false, insertable = false, updatable = false)
    }, foreignKey = @ForeignKey(name = "fk_evento_versao"))
    private PdiVersao pdiVersao;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumns(value = {
        @JoinColumn(name = "pdi_id", referencedColumnName = "pdi_id", insertable = false, updatable = false),
        @JoinColumn(name = "acao_pdi_id", referencedColumnName = "id", insertable = false, updatable = false)
    }, foreignKey = @ForeignKey(name = "fk_evento_acao"))
    private AcaoPDI acaoPdi;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumns(value = {
        @JoinColumn(name = "pdi_versao_id", referencedColumnName = "pdi_versao_id", insertable = false, updatable = false),
        @JoinColumn(name = "acao_pdi_id", referencedColumnName = "acao_pdi_id", insertable = false, updatable = false)
    }, foreignKey = @ForeignKey(name = "fk_evento_acao_versao"))
    private AcaoPdiVersao acaoPdiVersao;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumns(value = {
        @JoinColumn(name = "acao_pdi_id", referencedColumnName = "acao_pdi_id", insertable = false, updatable = false),
        @JoinColumn(name = "componente_id", referencedColumnName = "id", insertable = false, updatable = false)
    }, foreignKey = @ForeignKey(name = "fk_evento_componente"))
    private PdiComponente componente;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "submissao_id", updatable = false)
    private EntregaSubmissao submissao;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "autor_id", nullable = false, updatable = false)
    private Usuario autor;

    @Column(name = "tipo", nullable = false, length = 64, updatable = false)
    private String tipo;

    @Column(name = "ocorrido_em", nullable = false, updatable = false, columnDefinition = "timestamptz")
    private Instant ocorridoEm;

    @Column(name = "justificativa", updatable = false, columnDefinition = "text")
    private String justificativa;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "dados", nullable = false, updatable = false, columnDefinition = "jsonb")
    private JsonNode dados = JsonNodeFactory.instance.objectNode();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "snapshot", updatable = false, columnDefinition = "jsonb")
    private JsonNode snapshot;

    public PdiEvento(PdiVersao versao, AcaoPDI acao, AcaoPdiVersao versaoAcao, PdiComponente componente,
                     EntregaSubmissao submissao, Usuario autor, String tipo,
                     String justificativa, JsonNode dados, JsonNode snapshot) {
        if (versao == null || versao.getId() == null || versao.getPdi() == null
                || versao.getPdi().getId() == null) {
            throw new IllegalArgumentException("O evento exige uma versão e um PDI com IDs atribuídos");
        }
        if ((acao != null && acao.getId() == null)
                || (versaoAcao != null && versaoAcao.getId() == null)
                || (componente != null && componente.getId() == null)
                || (submissao != null && submissao.getId() == null)) {
            throw new IllegalArgumentException("Referências opcionais fornecidas devem possuir IDs atribuídos");
        }
        // A non-null action implies a versioned action through fk_evento_acao_versao.
        // Use its existing scalar IDs instead of traversing its LAZY associations.
        if ((acao == null) != (versaoAcao == null)) {
            throw new IllegalArgumentException("A ação e sua versão devem ser fornecidas em conjunto");
        }
        if (versaoAcao != null && (!versao.getId().equals(versaoAcao.getPdiVersaoId())
                || !versao.getPdi().getId().equals(versaoAcao.getPdiId())
                || !acao.getId().equals(versaoAcao.getAcaoPdiId()))) {
            throw new IllegalArgumentException("A ação, sua versão e a versão do PDI devem corresponder");
        }
        if ((componente != null && acao == null) || (submissao != null && componente == null)) {
            throw new IllegalArgumentException("Componente exige ação e submissão exige componente");
        }
        if (dados != null && !dados.isObject()) {
            throw new IllegalArgumentException("Dados deve ser um objeto JSON");
        }
        if (snapshot != null && !snapshot.isObject()) {
            throw new IllegalArgumentException("Snapshot deve ser um objeto JSON ou nulo");
        }
        this.pdiVersao = versao;
        this.pdi = versao.getPdi();
        this.pdiId = pdi.getId();
        this.pdiVersaoId = versao.getId();
        this.acaoPdi = acao;
        this.acaoPdiVersao = versaoAcao;
        this.acaoPdiId = acao == null ? null : acao.getId();
        this.componente = componente;
        this.componenteId = componente == null ? null : componente.getId();
        this.submissao = submissao;
        this.autor = autor;
        this.tipo = tipo;
        this.justificativa = justificativa;
        this.dados = dados == null ? JsonNodeFactory.instance.objectNode() : dados.deepCopy();
        this.snapshot = snapshot == null ? null : snapshot.deepCopy();
    }

    // Defensive copies prevent callers from mutating the historical JSON tree.
    public JsonNode getDados() {
        return dados == null ? null : dados.deepCopy();
    }

    public JsonNode getSnapshot() {
        return snapshot == null ? null : snapshot.deepCopy();
    }

    @PrePersist
    private void registrarOcorrencia() {
        // Application-produced values; SQL defaults remain available to other writers.
        ocorridoEm = Instant.now();
    }
}
