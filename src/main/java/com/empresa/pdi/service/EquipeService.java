package com.empresa.pdi.service;

import com.empresa.pdi.model.Equipe;
import com.empresa.pdi.model.Usuario;
import com.empresa.pdi.repository.EquipeRepository;
import com.empresa.pdi.repository.UsuarioRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

import static com.empresa.pdi.service.CoreValidacao.*;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class EquipeService {
    private final EquipeRepository equipes;
    private final UsuarioRepository usuarios;

    public List<Equipe> listar() {
        return equipes.findAll();
    }

    public Equipe buscar(UUID id) {
        return equipes.findById(obrigatorio(id, "id"))
                .orElseThrow(() -> new IllegalArgumentException("Equipe não encontrada"));
    }

    @Transactional
    public Equipe criar(String nome, String descricao) {
        Equipe equipe = new Equipe();
        equipe.setNome(obrigatorio(nome, "nome"));
        equipe.setDescricao(descricao);
        return equipes.save(equipe);
    }

    @Transactional
    public Equipe atualizar(UUID id, String nome, String descricao) {
        obrigatorio(nome, "nome");
        Equipe equipe = bloquear(id);
        equipe.setNome(nome);
        equipe.setDescricao(descricao);
        return equipes.save(equipe);
    }

    @Transactional
    public void inativar(UUID id) {
        Equipe equipe = bloquear(id);
        equipe.setAtivo(false);
        equipes.save(equipe);
    }

    @Transactional
    public void definirLider(UUID equipeId, UUID usuarioId) {
        obrigatorio(equipeId, "equipeId");
        Usuario lider = usuarios.findByIdForUpdate(obrigatorio(usuarioId, "usuarioId"))
                .orElseThrow(() -> new IllegalArgumentException("Usuário não encontrado"));
        // Same lock order as transfers: user, then team.
        Equipe equipe = bloquear(equipeId);
        exigir(lider.isAtivo(), "O líder deve estar ativo");
        exigir(lider.getEquipeAtual() != null
                        && Objects.equals(lider.getEquipeAtual().getId(), equipeId),
                "O líder deve pertencer à própria equipe");
        equipe.setLider(lider);
        equipes.save(equipe);
    }

    @Transactional
    public void removerLider(UUID equipeId) {
        Equipe equipe = bloquear(equipeId);
        equipe.setLider(null);
        equipes.save(equipe);
    }

    private Equipe bloquear(UUID id) {
        return equipes.findByIdForUpdate(obrigatorio(id, "id"))
                .orElseThrow(() -> new IllegalArgumentException("Equipe não encontrada"));
    }
}
