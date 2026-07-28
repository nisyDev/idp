package com.corp.idp.model;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Usuario {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private java.util.UUID id;

    private String nome;
    private String email;

    @ManyToOne
    @JoinColumn(name = "cargo_id")
    private Cargo cargo;

    @Enumerated(EnumType.STRING)
    private PerfilAcesso perfilAcesso;

    private boolean ativo = true;
}