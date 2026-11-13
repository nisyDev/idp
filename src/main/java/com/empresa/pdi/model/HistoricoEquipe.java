package com.empresa.pdi.model;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Check;
import org.hibernate.annotations.Immutable;
import java.time.Instant;
import java.util.UUID;

/** Append-only movement record. Deletion protection belongs to the service layer. */
@Entity
@Table(name = "historico_equipe")
@Check(constraints = "equipe_origem_id IS NULL OR equipe_origem_id <> equipe_destino_id")
@Immutable
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class HistoricoEquipe {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "usuario_id", nullable = false, updatable = false, foreignKey = @ForeignKey(name = "fk_historico_equipe_usuario"))
    private Usuario usuario;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "equipe_origem_id", updatable = false, foreignKey = @ForeignKey(name = "fk_historico_equipe_origem"))
    private Equipe equipeOrigem;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "equipe_destino_id", nullable = false, updatable = false, foreignKey = @ForeignKey(name = "fk_historico_equipe_destino"))
    private Equipe equipeDestino;

    @Column(name = "data_transferencia", nullable = false, updatable = false, columnDefinition = "timestamptz")
    private Instant dataTransferencia;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "realizado_por_id", updatable = false, foreignKey = @ForeignKey(name = "fk_historico_equipe_realizado_por"))
    private Usuario realizadoPor;

    @Column(name = "observacao", updatable = false, columnDefinition = "text")
    private String observacao;

    public HistoricoEquipe(Usuario usuario, Equipe equipeOrigem, Equipe equipeDestino,
                           Instant dataTransferencia, Usuario realizadoPor, String observacao) {
        this.usuario = usuario;
        this.equipeOrigem = equipeOrigem;
        this.equipeDestino = equipeDestino;
        this.dataTransferencia = dataTransferencia;
        this.realizadoPor = realizadoPor;
        this.observacao = observacao;
    }
}
