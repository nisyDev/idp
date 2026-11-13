package com.empresa.pdi.service;

import com.empresa.pdi.model.*;
import com.empresa.pdi.repository.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static com.empresa.pdi.service.CoreFixtures.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class EquipeServiceTest {
    @Mock EquipeRepository equipes;
    @Mock UsuarioRepository usuarios;

    @Test
    void criaEquipeSemLiderParaPermitirCadastroInicial() {
        when(equipes.save(any())).thenAnswer(i -> i.getArgument(0));
        Equipe equipe = new EquipeService(equipes, usuarios).criar("Equipe", null);
        assertTrue(equipe.isAtivo());
        assertNull(equipe.getLider());
    }

    @Test
    void exigeNome() {
        assertThrows(IllegalArgumentException.class,
                () -> new EquipeService(equipes, usuarios).criar(null, null));
        verifyNoInteractions(equipes, usuarios);
    }

    @Test
    void defineLiderAtivoDaPropriaEquipeComOrdemDeLocks() {
        Equipe equipe = equipe(ORIGEM);
        Usuario lider = usuario(equipe);
        when(usuarios.findByIdForUpdate(USUARIO)).thenReturn(Optional.of(lider));
        when(equipes.findByIdForUpdate(ORIGEM)).thenReturn(Optional.of(equipe));
        new EquipeService(equipes, usuarios).definirLider(ORIGEM, USUARIO);
        assertSame(lider, equipe.getLider());
        var ordem = inOrder(usuarios, equipes);
        ordem.verify(usuarios).findByIdForUpdate(USUARIO);
        ordem.verify(equipes).findByIdForUpdate(ORIGEM);
        ordem.verify(equipes).save(equipe);
    }

    @Test
    void rejeitaLiderInativo() {
        Equipe equipe = equipe(ORIGEM);
        Usuario lider = usuario(equipe);
        lider.setAtivo(false);
        when(usuarios.findByIdForUpdate(USUARIO)).thenReturn(Optional.of(lider));
        when(equipes.findByIdForUpdate(ORIGEM)).thenReturn(Optional.of(equipe));
        assertThrows(IllegalArgumentException.class,
                () -> new EquipeService(equipes, usuarios).definirLider(ORIGEM, USUARIO));
        assertNull(equipe.getLider());
        verify(equipes, never()).save(any());
    }

    @Test
    void rejeitaLiderDeOutraEquipe() {
        when(usuarios.findByIdForUpdate(USUARIO)).thenReturn(Optional.of(usuario(equipe(DESTINO))));
        when(equipes.findByIdForUpdate(ORIGEM)).thenReturn(Optional.of(equipe(ORIGEM)));
        assertThrows(IllegalArgumentException.class,
                () -> new EquipeService(equipes, usuarios).definirLider(ORIGEM, USUARIO));
        verify(equipes, never()).save(any());
    }

    @Test
    void removeLiderSemExcluirUsuario() {
        Equipe equipe = equipe(ORIGEM);
        equipe.setLider(usuario(equipe));
        when(equipes.findByIdForUpdate(ORIGEM)).thenReturn(Optional.of(equipe));
        new EquipeService(equipes, usuarios).removerLider(ORIGEM);
        assertNull(equipe.getLider());
        verifyNoInteractions(usuarios);
    }

    @Test
    void inativaEquipePreservandoLiderEVinculos() {
        Equipe equipe = equipe(ORIGEM);
        Usuario lider = usuario(equipe);
        equipe.setLider(lider);
        when(equipes.findByIdForUpdate(ORIGEM)).thenReturn(Optional.of(equipe));
        new EquipeService(equipes, usuarios).inativar(ORIGEM);
        assertFalse(equipe.isAtivo());
        assertSame(lider, equipe.getLider());
        assertSame(equipe, lider.getEquipeAtual());
        verify(equipes, never()).delete(any());
        verify(equipes, never()).deleteById(any());
    }

    @Test
    void rejeitaLiderInexistenteSemAlterarEquipe() {
        assertThrows(IllegalArgumentException.class,
                () -> new EquipeService(equipes, usuarios).definirLider(ORIGEM, USUARIO));
        verifyNoInteractions(equipes);
    }
}
