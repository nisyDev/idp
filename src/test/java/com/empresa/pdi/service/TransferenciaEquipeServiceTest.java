package com.empresa.pdi.service;

import com.empresa.pdi.model.*;
import com.empresa.pdi.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

import java.time.Instant;
import java.util.Optional;

import static com.empresa.pdi.service.CoreFixtures.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TransferenciaEquipeServiceTest {
    @Mock UsuarioRepository usuarios;
    @Mock EquipeRepository equipes;
    @Mock HistoricoEquipeRepository historicos;
    TransferenciaEquipeService service;
    Equipe origem;
    Equipe destino;
    Usuario usuario;
    final Instant data = Instant.parse("2026-11-04T12:00:00Z");

    @BeforeEach
    void configurar() {
        service = new TransferenciaEquipeService(usuarios, equipes, historicos);
        origem = equipe(ORIGEM);
        destino = equipe(DESTINO);
        usuario = usuario(origem);
    }

    private void prepararUsuario() {
        when(usuarios.findByIdForUpdate(USUARIO)).thenReturn(Optional.of(usuario));
    }

    private void prepararEquipes() {
        prepararUsuario();
        when(equipes.findByIdForUpdate(ORIGEM)).thenReturn(Optional.of(origem));
        when(equipes.findByIdForUpdate(DESTINO)).thenReturn(Optional.of(destino));
    }

    @Test
    void transfereComHistoricoEOrdenaLocks() {
        prepararEquipes();
        when(usuarios.findById(USUARIO)).thenReturn(Optional.of(usuario));
        when(historicos.save(any())).thenAnswer(i -> i.getArgument(0));
        HistoricoEquipe resultado = service.transferir(USUARIO, DESTINO, data, USUARIO, "Mudança");
        assertSame(destino, usuario.getEquipeAtual());
        assertSame(usuario, resultado.getUsuario());
        assertSame(origem, resultado.getEquipeOrigem());
        assertSame(destino, resultado.getEquipeDestino());
        assertSame(usuario, resultado.getRealizadoPor());
        assertEquals(data, resultado.getDataTransferencia());
        assertEquals("Mudança", resultado.getObservacao());
        var ordem = inOrder(usuarios, equipes, historicos);
        ordem.verify(usuarios).findByIdForUpdate(USUARIO);
        ordem.verify(equipes).findByIdForUpdate(DESTINO);
        ordem.verify(equipes).findByIdForUpdate(ORIGEM);
        ordem.verify(usuarios).findById(USUARIO);
        ordem.verify(usuarios).save(usuario);
        ordem.verify(historicos).save(resultado);
    }

    @Test
    void permiteEventoAutomaticoSemResponsavel() {
        prepararEquipes();
        when(historicos.save(any())).thenAnswer(i -> i.getArgument(0));
        assertNull(service.transferir(USUARIO, DESTINO, data, null, null).getRealizadoPor());
        verify(usuarios, never()).findById(any());
    }

    @Test
    void rejeitaMesmaEquipeSemGravar() {
        prepararUsuario();
        assertThrows(IllegalArgumentException.class,
                () -> service.transferir(USUARIO, ORIGEM, data, null, null));
        verifyNoInteractions(equipes, historicos);
        verify(usuarios, never()).save(any());
    }

    @Test
    void segundaSolicitacaoParaMesmoDestinoLeEstadoAtualizadoAposLock() {
        prepararEquipes();
        service.transferir(USUARIO, DESTINO, data, null, null);
        assertThrows(IllegalArgumentException.class,
                () -> service.transferir(USUARIO, DESTINO, data, null, null));
        verify(usuarios, times(2)).findByIdForUpdate(USUARIO);
        verify(historicos, times(1)).save(any());
    }

    @Test
    void rejeitaDestinoInativo() {
        prepararEquipes();
        destino.setAtivo(false);
        assertThrows(IllegalArgumentException.class,
                () -> service.transferir(USUARIO, DESTINO, data, null, null));
        assertSame(origem, usuario.getEquipeAtual());
        verifyNoInteractions(historicos);
        verify(usuarios, never()).save(any());
    }

    @Test
    void rejeitaTransferenciaDeLiderSemSubstituicaoOuRemocao() {
        prepararEquipes();
        origem.setLider(usuario);
        assertThrows(IllegalArgumentException.class,
                () -> service.transferir(USUARIO, DESTINO, data, null, null));
        verifyNoInteractions(historicos);
    }

    @Test
    void permiteTransferenciaAposSubstituicaoDoLider() {
        prepararEquipes();
        Usuario substituto = new Usuario();
        origem.setLider(substituto);
        service.transferir(USUARIO, DESTINO, data, null, null);
        verify(historicos).save(any());
    }

    @Test
    void rejeitaUsuarioInexistente() {
        when(usuarios.findByIdForUpdate(USUARIO)).thenReturn(Optional.empty());
        assertThrows(IllegalArgumentException.class,
                () -> service.transferir(USUARIO, DESTINO, data, null, null));
        verifyNoInteractions(equipes, historicos);
    }

    @Test
    void rejeitaEquipeInexistente() {
        prepararUsuario();
        when(equipes.findByIdForUpdate(DESTINO)).thenReturn(Optional.empty());
        assertThrows(IllegalArgumentException.class,
                () -> service.transferir(USUARIO, DESTINO, data, null, null));
        verifyNoInteractions(historicos);
    }

    @Test
    void rejeitaResponsavelInexistenteAntesDeAlterarUsuario() {
        prepararEquipes();
        when(usuarios.findById(USUARIO)).thenReturn(Optional.empty());
        assertThrows(IllegalArgumentException.class,
                () -> service.transferir(USUARIO, DESTINO, data, USUARIO, null));
        assertSame(origem, usuario.getEquipeAtual());
        verifyNoInteractions(historicos);
    }

    @Test
    void exigeDadosObrigatoriosAntesDeAcessarRepositorios() {
        assertThrows(IllegalArgumentException.class,
                () -> service.transferir(null, DESTINO, data, null, null));
        assertThrows(IllegalArgumentException.class,
                () -> service.transferir(USUARIO, null, data, null, null));
        assertThrows(IllegalArgumentException.class,
                () -> service.transferir(USUARIO, DESTINO, null, null, null));
        verifyNoInteractions(usuarios, equipes, historicos);
    }

    @Test
    void propagaFalhaDeLockSemGravar() {
        var falha = new PessimisticLockingFailureException("lock indisponível");
        when(usuarios.findByIdForUpdate(USUARIO)).thenThrow(falha);
        assertSame(falha, assertThrows(PessimisticLockingFailureException.class,
                () -> service.transferir(USUARIO, DESTINO, data, null, null)));
        verifyNoInteractions(equipes, historicos);
    }

    @Test
    void falhaAoSalvarUsuarioNaoGravaHistoricoEReverteTransacao() {
        prepararEquipes();
        var falha = new IllegalStateException("falha ao salvar usuário");
        when(usuarios.save(usuario)).thenThrow(falha);
        var transacoes = mock(PlatformTransactionManager.class);
        var status = mock(TransactionStatus.class);
        TransferenciaEquipeService proxy = proxyTransacional(transacoes, status);
        assertSame(falha, assertThrows(IllegalStateException.class,
                () -> proxy.transferir(USUARIO, DESTINO, data, null, null)));
        verify(transacoes).rollback(status);
        verify(transacoes, never()).commit(any());
        verifyNoInteractions(historicos);
    }

    @Test
    void falhaNoHistoricoReverteTransacaoSemCommitParcial() {
        prepararEquipes();
        var falha = new IllegalStateException("falha no histórico");
        when(historicos.save(any())).thenThrow(falha);
        var transacoes = mock(PlatformTransactionManager.class);
        var status = mock(TransactionStatus.class);
        var proxy = proxyTransacional(transacoes, status);
        assertSame(falha, assertThrows(IllegalStateException.class,
                () -> proxy.transferir(USUARIO, DESTINO, data, null, null)));
        verify(usuarios).save(usuario);
        verify(transacoes).rollback(status);
        verify(transacoes, never()).commit(any());
    }

    @Test
    void sucessoConfirmaUmaTransacaoParaUsuarioEHistorico() {
        prepararEquipes();
        var transacoes = mock(PlatformTransactionManager.class);
        var status = mock(TransactionStatus.class);
        proxyTransacional(transacoes, status).transferir(USUARIO, DESTINO, data, null, null);
        verify(transacoes).commit(status);
        verify(transacoes, never()).rollback(any());
        verify(historicos).save(any());
    }

    private TransferenciaEquipeService proxyTransacional(PlatformTransactionManager manager,
                                                        TransactionStatus status) {
        when(manager.getTransaction(any(TransactionDefinition.class))).thenReturn(status);
        ProxyFactory factory = new ProxyFactory(service);
        factory.setProxyTargetClass(true);
        TransactionInterceptor interceptor = new TransactionInterceptor();
        interceptor.setTransactionManager(manager);
        interceptor.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
        factory.addAdvice(interceptor);
        return (TransferenciaEquipeService) factory.getProxy();
    }
}
