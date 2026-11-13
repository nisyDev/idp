package com.empresa.pdi.service;

import com.empresa.pdi.enums.NivelCargo;
import com.empresa.pdi.model.Cargo;
import com.empresa.pdi.repository.CargoRepository;
import com.empresa.pdi.security.UsuarioAutenticadoProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.UUID;

import static com.empresa.pdi.service.CoreFixtures.CARGO;
import static com.empresa.pdi.service.CoreFixtures.USUARIO;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CargoConsultaServiceTest {
    @Mock CargoRepository cargos;
    @Mock UsuarioAutenticadoProvider autenticacao;

    @Test
    void autenticaAntesDeConsultarSomenteCargosAtivosEMapeiaTodosOsCampos() {
        Cargo analista = cargo(CARGO, "Analista", "Análise de sistemas", NivelCargo.PLENO);
        UUID outroId = new UUID(0, 4);
        Cargo assistente = cargo(outroId, "Assistente", null, NivelCargo.NAO_APLICAVEL);
        when(autenticacao.obterIdUsuarioAutenticado()).thenReturn(USUARIO);
        when(cargos.findByAtivoTrue()).thenReturn(List.of(analista, assistente));

        var resultado = new CargoConsultaService(cargos, autenticacao).listar();

        assertEquals(2, resultado.size());
        var primeiro = resultado.get(0);
        var segundo = resultado.get(1);
        assertAll(
                () -> assertEquals(CARGO, primeiro.id()),
                () -> assertEquals("Analista", primeiro.nome()),
                () -> assertEquals("Análise de sistemas", primeiro.descricao()),
                () -> assertEquals(NivelCargo.PLENO, primeiro.nivel()),
                () -> assertTrue(primeiro.ativo()),
                () -> assertEquals(outroId, segundo.id()),
                () -> assertEquals("Assistente", segundo.nome()),
                () -> assertNull(segundo.descricao()),
                () -> assertEquals(NivelCargo.NAO_APLICAVEL, segundo.nivel()),
                () -> assertTrue(segundo.ativo())
        );
        verificaConsultaAutenticada();
    }

    @Test
    void retornaListaVaziaQuandoNaoHaCargosAtivos() {
        when(autenticacao.obterIdUsuarioAutenticado()).thenReturn(USUARIO);
        when(cargos.findByAtivoTrue()).thenReturn(List.of());

        var resultado = new CargoConsultaService(cargos, autenticacao).listar();

        assertTrue(resultado.isEmpty());
        verificaConsultaAutenticada();
    }

    @Test
    void impedeConsultaSemAutenticacao() {
        var falha = new AuthenticationCredentialsNotFoundException("Autenticação necessária");
        when(autenticacao.obterIdUsuarioAutenticado()).thenThrow(falha);

        var resultado = assertThrows(AuthenticationCredentialsNotFoundException.class,
                () -> new CargoConsultaService(cargos, autenticacao).listar());

        assertSame(falha, resultado);
        verificaAusenciaDeConsulta();
    }

    @Test
    void impedeConsultaQuandoProviderRejeitaUsuarioInexistente() {
        verificaRejeicaoDeCredenciais();
    }

    @Test
    void impedeConsultaQuandoProviderRejeitaUsuarioInativo() {
        verificaRejeicaoDeCredenciais();
    }

    @Test
    void impedeConsultaQuandoProviderRetornaIdNulo() {
        when(autenticacao.obterIdUsuarioAutenticado()).thenReturn(null);

        var falha = assertThrows(BadCredentialsException.class,
                () -> new CargoConsultaService(cargos, autenticacao).listar());

        assertEquals("Autenticação inválida", falha.getMessage());
        verificaAusenciaDeConsulta();
    }

    private void verificaRejeicaoDeCredenciais() {
        var falha = new BadCredentialsException("Autenticação inválida");
        when(autenticacao.obterIdUsuarioAutenticado()).thenThrow(falha);

        var resultado = assertThrows(BadCredentialsException.class,
                () -> new CargoConsultaService(cargos, autenticacao).listar());

        assertSame(falha, resultado);
        verificaAusenciaDeConsulta();
    }

    private void verificaConsultaAutenticada() {
        var ordem = inOrder(autenticacao, cargos);
        ordem.verify(autenticacao).obterIdUsuarioAutenticado();
        ordem.verify(cargos).findByAtivoTrue();
        verifyNoMoreInteractions(autenticacao, cargos);
    }

    private void verificaAusenciaDeConsulta() {
        verify(autenticacao).obterIdUsuarioAutenticado();
        verifyNoMoreInteractions(autenticacao);
        verifyNoInteractions(cargos);
    }

    private static Cargo cargo(UUID id, String nome, String descricao, NivelCargo nivel) {
        Cargo cargo = new Cargo();
        ReflectionTestUtils.setField(cargo, "id", id);
        cargo.setNome(nome);
        cargo.setDescricao(descricao);
        cargo.setNivel(nivel);
        return cargo;
    }
}
