package com.empresa.pdi.model;

import com.empresa.pdi.enums.ResultadoEntregaSubmissao;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import java.time.Instant;

/** One submission attempt. Corrections require a new instance, not new evidence on this row. */
@Entity
@Table(name = "entrega_submissao")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class EntregaSubmissao {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "componente_versao_id", nullable = false, updatable = false)
    private PdiComponenteVersao componenteVersao;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "submetida_por_id", nullable = false, updatable = false)
    private Usuario submetidaPor;

    @Column(name = "submetida_em", nullable = false, updatable = false, columnDefinition = "timestamptz")
    private Instant submetidaEm;

    @Column(name = "comprovacao_texto", updatable = false, columnDefinition = "text")
    private String comprovacaoTexto;

    @Column(name = "comprovacao_url", updatable = false, columnDefinition = "text")
    private String comprovacaoUrl;

    @Enumerated(EnumType.STRING)
    @Column(name = "resultado", nullable = false, length = 32)
    @Setter
    private ResultadoEntregaSubmissao resultado = ResultadoEntregaSubmissao.PENDENTE;

    @Column(name = "encerrada_em", columnDefinition = "timestamptz")
    @Setter
    private Instant encerradaEm;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "decidida_por_id")
    @Setter
    private Usuario decididaPor;

    @Column(name = "justificativa", columnDefinition = "text")
    @Setter
    private String justificativa;

    public EntregaSubmissao(PdiComponenteVersao componenteVersao, Usuario submetidaPor,
                           String comprovacaoTexto, String comprovacaoUrl) {
        this.componenteVersao = componenteVersao;
        this.submetidaPor = submetidaPor;
        this.comprovacaoTexto = comprovacaoTexto;
        this.comprovacaoUrl = comprovacaoUrl;
    }

    @PrePersist
    private void registrarSubmissao() {
        submetidaEm = Instant.now();
    }
}
