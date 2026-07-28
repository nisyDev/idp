package com.corp.idp.model;

import jakarta.persistence.*;
import lombok.*;
import java.util.UUID;

@Entity
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Equipe {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    private String nome;

    @ManyToOne
    @JoinColumn(name = "lider_id")
    private Usuario lider;

    private String descricao;
    private boolean ativo = true;
}