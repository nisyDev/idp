package com.empresa.pdi.service;

import com.empresa.pdi.model.*;
import com.empresa.pdi.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static com.empresa.pdi.service.CoreValidacao.*;

@Service
@RequiredArgsConstructor
public class TransferenciaEquipeService {
    private final UsuarioRepository usuarios;
    private final EquipeRepository equipes;
    private final HistoricoEquipeRepository historicos;

    /** Null actor is reserved for automatic events/imports, as specified by V1. */
    @Transactional
    public HistoricoEquipe transferir(UUID usuarioId, UUID destinoId, Instant dataTransferencia,
                                     UUID realizadoPorId, String observacao) {
        obrigatorio(usuarioId, "usuarioId");
        obrigatorio(destinoId, "destinoId");
        obrigatorio(dataTransferencia, "dataTransferencia");
        Usuario usuario = usuarios.findByIdForUpdate(usuarioId)
                .orElseThrow(() -> new IllegalArgumentException("Usuário não encontrado"));
        UUID origemId = usuario.getEquipeAtual().getId();
        exigir(!origemId.equals(destinoId), "Origem e destino devem ser diferentes");

        // All team locks follow a stable order, including transfers in opposite directions.
        Map<UUID, Equipe> bloqueadas = new HashMap<>();
        Stream.of(origemId, destinoId).sorted().forEach(id -> bloqueadas.put(id,
                equipes.findByIdForUpdate(id)
                        .orElseThrow(() -> new IllegalArgumentException("Equipe não encontrada"))));
        Equipe origem = bloqueadas.get(origemId);
        Equipe destino = bloqueadas.get(destinoId);
        exigir(destino.isAtivo(), "Equipe de destino deve estar ativa");
        exigirNaoLider(usuario, origem);
        Usuario realizadoPor = realizadoPorId == null ? null : usuarios.findById(realizadoPorId)
                .orElseThrow(() -> new IllegalArgumentException("Responsável não encontrado"));

        HistoricoEquipe historico = new HistoricoEquipe(usuario, origem, destino,
                dataTransferencia, realizadoPor, observacao);
        usuario.setEquipeAtual(destino);
        usuarios.save(usuario);
        // Exceptions propagate to the transactional proxy; neither operation may commit alone.
        return historicos.save(historico);
    }
}
