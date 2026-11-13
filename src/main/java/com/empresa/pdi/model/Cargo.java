package com.empresa.pdi.model;

import com.empresa.pdi.enums.NivelCargo;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.Check;
import java.util.UUID;

@Entity
@Table(name = "cargo")
@Check(constraints = "nivel IN ('JUNIOR', 'PLENO', 'SENIOR', 'NAO_APLICAVEL')")
@Getter
@Setter
@NoArgsConstructor
public class Cargo {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    @Setter(AccessLevel.NONE)
    private UUID id;

    @Column(name = "nome", nullable = false, columnDefinition = "text")
    private String nome;

    @Column(name = "descricao", columnDefinition = "text")
    private String descricao;

    @Enumerated(EnumType.STRING)
    @Column(name = "nivel", nullable = false, length = 20)
    private NivelCargo nivel;

    @Column(name = "ativo", nullable = false)
    private boolean ativo = true;
}
