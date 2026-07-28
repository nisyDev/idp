package com.corp.idp.model;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PDI {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne
    @JoinColumn(name = "usuario_id")
    private Usuario usuario;

    private String titulo;
    private String objetivo;
    private LocalDate prazo;

    @Enumerated(EnumType.STRING)
    private StatusPDI statusPdi;
}