package com.empresa.pdi.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import java.time.LocalDate;

@Entity
@Table(name = "reuniao")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Reuniao {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String titulo;
    private LocalDate data;
    private String pauta;

    // Campo que vai mostrar "integração futura com Teams"
    // Por enquanto é só um link placeholder
    private String linkTeams;

    // Reunião é sempre entre colaborador e gestor
    @ManyToOne
    @JoinColumn(name = "colaborador_id")
    private Usuario colaborador;

    @ManyToOne
    @JoinColumn(name = "gestor_id")
    private Usuario gestor;
}