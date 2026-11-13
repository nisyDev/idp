package com.empresa.pdi.service;

import com.empresa.pdi.enums.NivelCargo;
import com.empresa.pdi.model.Cargo;
import com.empresa.pdi.repository.CargoRepository;
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
class CargoServiceTest {
    @Mock CargoRepository cargos;

    @Test
    void criaCargoAtivoSemLimitesTextuaisAdicionais() {
        when(cargos.save(any())).thenAnswer(i -> i.getArgument(0));
        Cargo cargo = new CargoService(cargos).criar("", null, NivelCargo.NAO_APLICAVEL);
        assertTrue(cargo.isAtivo());
        assertEquals("", cargo.getNome());
        assertNull(cargo.getDescricao());
        assertEquals(NivelCargo.NAO_APLICAVEL, cargo.getNivel());
    }

    @Test
    void exigeCamposObrigatorios() {
        var service = new CargoService(cargos);
        assertThrows(IllegalArgumentException.class, () -> service.criar(null, null, NivelCargo.JUNIOR));
        assertThrows(IllegalArgumentException.class, () -> service.criar("Cargo", null, null));
        verifyNoInteractions(cargos);
    }

    @Test
    void atualizaCargoSemRecriarRegistro() {
        Cargo cargo = cargo();
        when(cargos.findByIdForUpdate(CARGO)).thenReturn(Optional.of(cargo));
        new CargoService(cargos).atualizar(CARGO, "Analista", "Descrição", NivelCargo.PLENO);
        assertEquals(CARGO, cargo.getId());
        assertEquals("Analista", cargo.getNome());
        assertEquals(NivelCargo.PLENO, cargo.getNivel());
        verify(cargos).save(cargo);
    }

    @Test
    void inativaSemExcluir() {
        Cargo cargo = cargo();
        when(cargos.findByIdForUpdate(CARGO)).thenReturn(Optional.of(cargo));
        new CargoService(cargos).inativar(CARGO);
        assertFalse(cargo.isAtivo());
        verify(cargos).save(cargo);
        verify(cargos, never()).delete(any());
        verify(cargos, never()).deleteById(any());
    }

    @Test
    void rejeitaCargoInexistente() {
        assertThrows(IllegalArgumentException.class, () -> new CargoService(cargos).inativar(CARGO));
        verify(cargos, never()).save(any());
    }
}
