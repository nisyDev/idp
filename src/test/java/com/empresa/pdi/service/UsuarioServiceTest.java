package com.empresa.pdi.service;

import com.empresa.pdi.enums.PerfilUsuario;
import com.empresa.pdi.exception.UsuarioNaoEncontradoException;
import com.empresa.pdi.model.*;
import com.empresa.pdi.repository.*;
import com.empresa.pdi.security.UsuarioAutenticadoProvider;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static com.empresa.pdi.service.CoreFixtures.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UsuarioServiceTest {
    @Mock UsuarioRepository usuarios;
    @Mock CargoRepository cargos;
    @Mock EquipeRepository equipes;
    @Mock HistoricoEquipeRepository historicos;
    @Mock ObjectProvider<UsuarioAutenticadoProvider> autenticacao;
    UsuarioService service;

    @BeforeEach
    void configurar() {
        service = new UsuarioService(usuarios, cargos, equipes, historicos, autenticacao);
    }

    @Test
    void consultaUsuarioExistente() {
        Usuario usuario = usuario(equipe(ORIGEM));
        when(usuarios.findById(USUARIO)).thenReturn(Optional.of(usuario));
        assertSame(usuario, service.buscar(USUARIO));
    }

    @Test
    void consultaUsuarioInexistenteTemExcecaoEspecifica() {
        when(usuarios.findById(USUARIO)).thenReturn(Optional.empty());
        assertThrows(UsuarioNaoEncontradoException.class, () -> service.buscar(USUARIO));
    }

    @Test
    void consultaComIdNuloFalhaAntesDoRepositorio() {
        assertThrows(IllegalArgumentException.class, () -> service.buscar(null));
        verifyNoInteractions(usuarios, cargos, equipes, historicos);
    }

    @Test
    void criaComCargoEquipeAtivosEHistoricoInicial() {
        Cargo cargo = cargo();
        Equipe equipe = equipe(ORIGEM);
        when(cargos.findByIdForUpdate(CARGO)).thenReturn(Optional.of(cargo));
        when(equipes.findByIdForUpdate(ORIGEM)).thenReturn(Optional.of(equipe));
        Usuario usuario = service.criarAutomatico("ad", "Nome", "Email", PerfilUsuario.RH_ADMIN, CARGO, ORIGEM);
        assertEquals("ad", usuario.getAdObjectId());
        assertEquals("Email", usuario.getEmail());
        assertEquals(PerfilUsuario.RH_ADMIN, usuario.getPerfilAcesso());
        assertSame(cargo, usuario.getCargo());
        assertSame(equipe, usuario.getEquipeAtual());
        assertTrue(usuario.isAtivo());
        var captor = ArgumentCaptor.forClass(HistoricoEquipe.class);
        var ordem = inOrder(usuarios, historicos);
        ordem.verify(usuarios).saveAndFlush(usuario);
        ordem.verify(historicos).save(captor.capture());
        assertSame(usuario, captor.getValue().getUsuario());
        assertNull(captor.getValue().getEquipeOrigem());
        assertSame(equipe, captor.getValue().getEquipeDestino());
        assertNotNull(captor.getValue().getDataTransferencia());
        assertNull(captor.getValue().getRealizadoPor());
    }

    @Test
    void rejeitaCargoInativoNoCadastro() {
        Cargo cargo = cargo();
        cargo.setAtivo(false);
        when(cargos.findByIdForUpdate(CARGO)).thenReturn(Optional.of(cargo));
        assertThrows(IllegalArgumentException.class,
                () -> service.criarAutomatico(null, "Nome", "email", PerfilUsuario.COLABORADOR, CARGO, ORIGEM));
        verifyNoInteractions(usuarios, equipes, historicos);
    }

    @Test
    void rejeitaEquipeInativaNoCadastro() {
        Equipe equipe = equipe(ORIGEM);
        equipe.setAtivo(false);
        when(cargos.findByIdForUpdate(CARGO)).thenReturn(Optional.of(cargo()));
        when(equipes.findByIdForUpdate(ORIGEM)).thenReturn(Optional.of(equipe));
        assertThrows(IllegalArgumentException.class,
                () -> service.criarAutomatico(null, "Nome", "email", PerfilUsuario.COLABORADOR, CARGO, ORIGEM));
        verifyNoInteractions(usuarios, historicos);
    }

    @Test
    void exigeDadosObrigatoriosAntesDePersistir() {
        assertThrows(IllegalArgumentException.class,
                () -> service.criarAutomatico(null, null, "email", PerfilUsuario.COLABORADOR, CARGO, ORIGEM));
        assertThrows(IllegalArgumentException.class,
                () -> service.criarAutomatico(null, "Nome", null, PerfilUsuario.COLABORADOR, CARGO, ORIGEM));
        assertThrows(IllegalArgumentException.class,
                () -> service.criarAutomatico(null, "Nome", "email", null, CARGO, ORIGEM));
        verifyNoInteractions(usuarios, cargos, equipes, historicos);
    }

    @Test
    void alteraCargoSemCriarNovoUsuario() {
        Usuario usuario = usuario(equipe(ORIGEM));
        Cargo cargo = cargo();
        when(usuarios.findByIdForUpdate(USUARIO)).thenReturn(Optional.of(usuario));
        when(cargos.findByIdForUpdate(CARGO)).thenReturn(Optional.of(cargo));
        service.alterarCargo(USUARIO, CARGO);
        assertSame(cargo, usuario.getCargo());
        assertEquals(USUARIO, usuario.getId());
        verify(usuarios).save(usuario);
        verifyNoInteractions(historicos);
    }

    @Test
    void rejeitaNovaAtribuicaoACargoInativo() {
        Usuario usuario = usuario(equipe(ORIGEM));
        Cargo cargo = cargo();
        cargo.setAtivo(false);
        when(usuarios.findByIdForUpdate(USUARIO)).thenReturn(Optional.of(usuario));
        when(cargos.findByIdForUpdate(CARGO)).thenReturn(Optional.of(cargo));
        assertThrows(IllegalArgumentException.class, () -> service.alterarCargo(USUARIO, CARGO));
        assertNull(usuario.getCargo());
        verify(usuarios, never()).save(any());
    }

    @Test
    void preservaVinculoExistenteComCargoInativo() {
        Usuario usuario = usuario(equipe(ORIGEM));
        Cargo cargo = cargo();
        cargo.setAtivo(false);
        usuario.setCargo(cargo);
        when(usuarios.findByIdForUpdate(USUARIO)).thenReturn(Optional.of(usuario));
        service.alterarCargo(USUARIO, CARGO);
        assertSame(cargo, usuario.getCargo());
        verifyNoInteractions(cargos);
        verify(usuarios, never()).save(any());
    }

    @Test
    void atualizaDadosSemMudarGestorEquipeOuCargo() {
        Equipe equipe = equipe(ORIGEM);
        Usuario usuario = usuario(equipe);
        Usuario gestor = new Usuario();
        Cargo cargo = cargo();
        usuario.setGestor(gestor);
        usuario.setCargo(cargo);
        when(usuarios.findByIdForUpdate(USUARIO)).thenReturn(Optional.of(usuario));
        service.atualizarDados(USUARIO, "Novo nome", "novo email", PerfilUsuario.GESTOR);
        assertEquals("Novo nome", usuario.getNome());
        assertEquals("novo email", usuario.getEmail());
        assertSame(gestor, usuario.getGestor());
        assertSame(equipe, usuario.getEquipeAtual());
        assertSame(cargo, usuario.getCargo());
        verifyNoInteractions(cargos, equipes, historicos);
    }

    @Test
    void inativaPreservandoEquipeCargoEHistorico() {
        Equipe equipe = equipe(ORIGEM);
        Usuario usuario = usuario(equipe);
        Cargo cargo = cargo();
        usuario.setCargo(cargo);
        when(usuarios.findByIdForUpdate(USUARIO)).thenReturn(Optional.of(usuario));
        when(equipes.findByIdForUpdate(ORIGEM)).thenReturn(Optional.of(equipe));
        service.inativar(USUARIO);
        assertFalse(usuario.isAtivo());
        assertSame(equipe, usuario.getEquipeAtual());
        assertSame(cargo, usuario.getCargo());
        verify(usuarios, never()).delete(any());
        verify(usuarios, never()).deleteById(any());
        verifyNoInteractions(historicos, cargos);
    }

    @Test
    void exigeRemoverOuSubstituirLiderAntesDeInativarUsuario() {
        Equipe equipe = equipe(ORIGEM);
        Usuario usuario = usuario(equipe);
        equipe.setLider(usuario);
        when(usuarios.findByIdForUpdate(USUARIO)).thenReturn(Optional.of(usuario));
        when(equipes.findByIdForUpdate(ORIGEM)).thenReturn(Optional.of(equipe));
        assertThrows(IllegalArgumentException.class, () -> service.inativar(USUARIO));
        assertTrue(usuario.isAtivo());
        verify(usuarios, never()).save(any());
    }

    @Test
    void falhaNoCadastroNaoRegistraHistorico() {
        when(cargos.findByIdForUpdate(CARGO)).thenReturn(Optional.of(cargo()));
        when(equipes.findByIdForUpdate(ORIGEM)).thenReturn(Optional.of(equipe(ORIGEM)));
        when(usuarios.saveAndFlush(any())).thenThrow(new IllegalStateException("persistência"));
        assertThrows(IllegalStateException.class,
                () -> service.criarAutomatico(null, "Nome", "email", PerfilUsuario.COLABORADOR, CARGO, ORIGEM));
        verifyNoInteractions(historicos);
    }

    @Test
    void cadastroManualSemIntegracaoFalhaAntesDePersistir() {
        assertThrows(IllegalStateException.class,
                () -> service.criarManual(null, "Nome", "email", PerfilUsuario.COLABORADOR, CARGO, ORIGEM));
        verifyNoInteractions(usuarios, cargos, equipes, historicos);
    }

    @Test
    void cadastroManualSemIdentidadeFalhaSemUsarContextoAutomatico() {
        UsuarioAutenticadoProvider provider = mock(UsuarioAutenticadoProvider.class);
        when(autenticacao.getIfAvailable()).thenReturn(provider);
        when(provider.obterIdUsuarioAutenticado()).thenReturn(null);
        assertThrows(IllegalStateException.class,
                () -> service.criarManual(null, "Nome", "email", PerfilUsuario.COLABORADOR, CARGO, ORIGEM));
        verifyNoInteractions(usuarios, cargos, equipes, historicos);
    }

    @Test
    void cadastroManualComAutenticadoNaoCadastradoFalhaAntesDePersistir() {
        when(autenticacao.getIfAvailable()).thenReturn(() -> USUARIO);
        when(usuarios.findById(USUARIO)).thenReturn(Optional.empty());
        assertThrows(IllegalStateException.class,
                () -> service.criarManual(null, "Nome", "email", PerfilUsuario.COLABORADOR, CARGO, ORIGEM));
        verify(usuarios, never()).saveAndFlush(any());
        verifyNoInteractions(cargos, equipes, historicos);
    }

    @Test
    void cadastroManualRegistraResponsavelObtidoExclusivamenteDoProvider() {
        Usuario responsavel = usuario(equipe(ORIGEM));
        when(autenticacao.getIfAvailable()).thenReturn(() -> USUARIO);
        when(usuarios.findById(USUARIO)).thenReturn(Optional.of(responsavel));
        prepararCadastro();
        Usuario criado = service.criarManual(null, "Nome", "email", PerfilUsuario.COLABORADOR, CARGO, ORIGEM);
        var captor = ArgumentCaptor.forClass(HistoricoEquipe.class);
        verify(historicos).save(captor.capture());
        assertSame(responsavel, captor.getValue().getRealizadoPor());
        assertSame(criado, captor.getValue().getUsuario());
        assertNotSame(responsavel, criado);
        assertNull(captor.getValue().getEquipeOrigem());
    }

    @Test
    void cadastroAutomaticoNaoConsultaIdentidadeMesmoSeHouverProvider() {
        prepararCadastro();
        service.criarAutomatico(null, "Nome", "email", PerfilUsuario.COLABORADOR, CARGO, ORIGEM);
        var captor = ArgumentCaptor.forClass(HistoricoEquipe.class);
        verify(historicos).save(captor.capture());
        assertNull(captor.getValue().getRealizadoPor());
        verifyNoInteractions(autenticacao);
        verify(usuarios, never()).findById(any());
    }

    @Test
    void importacaoExplicitaRegistraHistoricoSemResponsavel() {
        prepararCadastro();
        Usuario criado = service.criarPorImportacao(null, "Nome", "email", PerfilUsuario.COLABORADOR, CARGO, ORIGEM);
        var captor = ArgumentCaptor.forClass(HistoricoEquipe.class);
        verify(historicos).save(captor.capture());
        assertSame(criado, captor.getValue().getUsuario());
        assertNull(captor.getValue().getRealizadoPor());
        assertNull(captor.getValue().getEquipeOrigem());
        verifyNoInteractions(autenticacao);
    }

    @Test
    void falhaDoProviderNaoCaiEmCadastroAutomatico() {
        var falha = new IllegalStateException("Identidade indisponível");
        UsuarioAutenticadoProvider provider = mock(UsuarioAutenticadoProvider.class);
        when(autenticacao.getIfAvailable()).thenReturn(provider);
        when(provider.obterIdUsuarioAutenticado()).thenThrow(falha);
        assertSame(falha, assertThrows(IllegalStateException.class,
                () -> service.criarManual(null, "Nome", "email", PerfilUsuario.COLABORADOR, CARGO, ORIGEM)));
        verifyNoInteractions(usuarios, cargos, equipes, historicos);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"manual", "automatico", "importacao"})
    void falhaNoHistoricoSolicitaRollbackDoCadastroInteiro(String contexto) {
        prepararCadastro();
        if (contexto.equals("manual")) {
            when(autenticacao.getIfAvailable()).thenReturn(() -> USUARIO);
            when(usuarios.findById(USUARIO)).thenReturn(Optional.of(usuario(equipe(ORIGEM))));
        }
        var falha = new IllegalStateException("Falha no histórico");
        when(historicos.save(any())).thenThrow(falha);
        PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
        TransactionStatus status = mock(TransactionStatus.class);
        when(manager.getTransaction(any(TransactionDefinition.class))).thenReturn(status);
        TransactionInterceptor interceptor = new TransactionInterceptor();
        interceptor.setTransactionManager(manager);
        interceptor.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
        ProxyFactory factory = new ProxyFactory(service);
        factory.setProxyTargetClass(true);
        factory.addAdvice(interceptor);
        UsuarioService proxy = (UsuarioService) factory.getProxy();

        assertSame(falha, assertThrows(IllegalStateException.class, () -> {
            switch (contexto) {
                case "manual" -> proxy.criarManual(null, "Nome", "email", PerfilUsuario.COLABORADOR, CARGO, ORIGEM);
                case "automatico" -> proxy.criarAutomatico(null, "Nome", "email", PerfilUsuario.COLABORADOR, CARGO, ORIGEM);
                case "importacao" -> proxy.criarPorImportacao(null, "Nome", "email", PerfilUsuario.COLABORADOR, CARGO, ORIGEM);
                default -> throw new AssertionError("Contexto de teste desconhecido");
            }
        }));
        verify(usuarios).saveAndFlush(any());
        verify(manager).rollback(status);
        verify(manager, never()).commit(any());
    }

    private void prepararCadastro() {
        when(cargos.findByIdForUpdate(CARGO)).thenReturn(Optional.of(cargo()));
        when(equipes.findByIdForUpdate(ORIGEM)).thenReturn(Optional.of(equipe(ORIGEM)));
    }
}
