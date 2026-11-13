package com.empresa.pdi.model;

import com.empresa.pdi.enums.StatusValidacaoComponente;
import com.empresa.pdi.enums.TipoComponentePdi;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "pdi_componente", uniqueConstraints =
    @UniqueConstraint(name = "uq_componente_pertencimento", columnNames = {"acao_pdi_id", "id"}))
@Getter
@Setter
@NoArgsConstructor
public class PdiComponente {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Setter(AccessLevel.NONE)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "acao_pdi_id", nullable = false)
    private AcaoPDI acaoPdi;

    @Enumerated(EnumType.STRING)
    @Column(name = "tipo", nullable = false, length = 32)
    private TipoComponentePdi tipo = TipoComponentePdi.ENTREGA_VERIFICAVEL;

    @Enumerated(EnumType.STRING)
    @Column(name = "status_validacao", nullable = false, length = 32)
    private StatusValidacaoComponente statusValidacao = StatusValidacaoComponente.PENDENTE;
}
