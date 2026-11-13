package com.empresa.pdi.model;

import com.empresa.pdi.enums.StatusExecucaoAcao;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;
import java.time.Instant;

@Entity
@Table(name = "acao_pdi", uniqueConstraints =
    @UniqueConstraint(name = "uq_acao_pdi_pertencimento", columnNames = {"pdi_id", "id"}))
@Getter
@Setter
@NoArgsConstructor
public class AcaoPDI {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Setter(AccessLevel.NONE)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "status_execucao", nullable = false, length = 32)
    private StatusExecucaoAcao statusExecucao = StatusExecucaoAcao.NAO_INICIADA;

    @Column(name = "iniciada_em", columnDefinition = "timestamptz")
    private Instant iniciadaEm;

    @Column(name = "concluida_em", columnDefinition = "timestamptz")
    private Instant concluidaEm;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "concluida_por_id")
    private Usuario concluidaPor;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "pdi_id", nullable = false)
    private PDI pdi;
}
