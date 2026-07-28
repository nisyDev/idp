package com.corp.idp.model;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AcaoPDI {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne
    @JoinColumn(name = "pdi_id")
    private PDI pdi;

    private String titulo;
    private String descricao;

    @Enumerated(EnumType.STRING)
    private TipoAcao tipoAcao;

    private LocalDate prazo;

    @Enumerated(EnumType.STRING)
    private StatusAcao statusAcao;

    private String recurso;
    private String metrica;
    private LocalDate dataConclusao;
    private Integer ordem;
}