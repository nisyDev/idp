package com.empresa.pdi.service;

import com.empresa.pdi.enums.NivelCargo;
import com.empresa.pdi.model.Cargo;
import com.empresa.pdi.repository.CargoRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

import static com.empresa.pdi.service.CoreValidacao.obrigatorio;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CargoService {
    private final CargoRepository cargos;

    public List<Cargo> listar() {
        return cargos.findAll();
    }

    public Cargo buscar(UUID id) {
        return cargos.findById(obrigatorio(id, "id"))
                .orElseThrow(() -> new IllegalArgumentException("Cargo não encontrado"));
    }

    @Transactional
    public Cargo criar(String nome, String descricao, NivelCargo nivel) {
        obrigatorio(nome, "nome");
        obrigatorio(nivel, "nivel");
        Cargo cargo = new Cargo();
        cargo.setNome(nome);
        cargo.setDescricao(descricao);
        cargo.setNivel(nivel);
        return cargos.save(cargo);
    }

    @Transactional
    public Cargo atualizar(UUID id, String nome, String descricao, NivelCargo nivel) {
        obrigatorio(nome, "nome");
        obrigatorio(nivel, "nivel");
        Cargo cargo = bloquear(id);
        cargo.setNome(nome);
        cargo.setDescricao(descricao);
        cargo.setNivel(nivel);
        return cargos.save(cargo);
    }

    @Transactional
    public void inativar(UUID id) {
        Cargo cargo = bloquear(id);
        cargo.setAtivo(false);
        cargos.save(cargo);
    }

    private Cargo bloquear(UUID id) {
        return cargos.findByIdForUpdate(obrigatorio(id, "id"))
                .orElseThrow(() -> new IllegalArgumentException("Cargo não encontrado"));
    }
}
