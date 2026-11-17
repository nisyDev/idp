package com.empresa.pdi.service;

import com.empresa.pdi.enums.PerfilUsuario;
import com.empresa.pdi.enums.StatusAprovacaoPdi;
import com.empresa.pdi.exception.PdiRascunhoNaoEditavelException;
import com.empresa.pdi.exception.PdiRascunhoNaoEncontradoException;
import com.empresa.pdi.model.*;
import com.empresa.pdi.repository.*;
import com.empresa.pdi.security.UsuarioAutenticadoProvider;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Authorization is tested with fresh server-side entities and no database. */
@ExtendWith(MockitoExtension.class)
class PdiAcessoServiceTest {
    private static final UUID OWNER = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID MANAGER = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID RH = UUID.fromString("33333333-3333-4333-8333-333333333333");
    @Mock UsuarioRepository usuarios;
    @Mock PDIRepository pdis;
    @Mock PdiVersaoRepository versoes;
    @Mock EntityManager em;
    @Mock ObjectProvider<UsuarioAutenticadoProvider> autenticacao;
    @Mock UsuarioAutenticadoProvider principal;
    private PdiAcessoService acesso;
    private Usuario owner;
    private Usuario manager;
    private Usuario rh;
    private PdiVersao version;

    @BeforeEach
    void fixtures() {
        acesso = policy("");
        owner = user(OWNER, PerfilUsuario.COLABORADOR);
        manager = user(MANAGER, PerfilUsuario.GESTOR);
        rh = user(RH, PerfilUsuario.RH_ADMIN);
        owner.setGestor(manager);
        PDI pdi = new PDI();
        ReflectionTestUtils.setField(pdi, "id", 11L);
        pdi.setUsuario(owner);
        version = new PdiVersao();
        ReflectionTestUtils.setField(version, "id", 21L);
        version.setPdi(pdi);
        version.setNumero(1);
    }

    @Test
    void currentManagerCanDecideButFormerManagerLosesPermissionImmediately() {
        assertDoesNotThrow(() -> acesso.validarDecisor(manager, owner));
        owner.setGestor(rh);
        assertThrows(PdiRascunhoNaoEncontradoException.class, () -> acesso.validarDecisor(manager, owner));
    }

    @Test
    void managerRelationshipAloneDoesNotGrantManagerRole() {
        manager.setPerfilAcesso(PerfilUsuario.COLABORADOR);
        assertStatus(HttpStatus.FORBIDDEN, () -> acesso.validarDecisor(manager, owner));
    }

    @Test
    void rhRoleRequiresExplicitAllowlistAndActiveAccount() {
        assertStatus(HttpStatus.FORBIDDEN, () -> acesso.validarDecisor(rh, owner));
        acesso = policy("  " + RH + "  ");
        assertDoesNotThrow(() -> acesso.validarDecisor(rh, owner));
        rh.setAtivo(false);
        assertThrows(BadCredentialsException.class, () -> acesso.validarDecisor(rh, owner));
    }

    @Test
    void allowlistedCollaboratorDoesNotGainRhPermission() {
        acesso = policy(OWNER.toString());
        assertStatus(HttpStatus.FORBIDDEN, () -> acesso.validarDecisor(owner, manager));
    }

    @Test
    void selfDecisionIsForbiddenEvenForExplicitlyAuthorizedRh() {
        acesso = policy(RH.toString());
        assertStatus(HttpStatus.FORBIDDEN, () -> acesso.validarDecisor(rh, rh));
        assertStatus(HttpStatus.FORBIDDEN, () -> acesso.validarDecisor(manager, manager));
    }

    @Test
    void inactiveManagerAndInactiveOwnerCannotReceiveDecision() {
        manager.setAtivo(false);
        assertThrows(BadCredentialsException.class, () -> acesso.validarDecisor(manager, owner));
        manager.setAtivo(true);
        owner.setAtivo(false);
        assertStatus(HttpStatus.CONFLICT, () -> acesso.validarDecisor(manager, owner));
    }

    @Test
    void privateRevisionIsInvisibleToManagerAndAuthorizedRh() {
        acesso = policy(RH.toString());
        assertTrue(acesso.podeConsultar(version, owner));
        assertFalse(acesso.podeConsultar(version, manager));
        assertFalse(acesso.podeConsultar(version, rh));
        version.setSubmetidaEm(Instant.now());
        assertTrue(acesso.podeConsultar(version, manager));
        assertTrue(acesso.podeConsultar(version, rh));
        owner.setGestor(rh);
        assertFalse(acesso.podeConsultar(version, manager));
    }

    @Test
    void wrongVersionOrMissingRevisionGuardCannotMutateDraft() {
        assertDoesNotThrow(() -> PdiAcessoService.validarRascunho(version, null));
        assertThrows(PdiRascunhoNaoEditavelException.class,
                () -> PdiAcessoService.validarRascunho(version, 999L));
        version.setNumero(2);
        assertThrows(PdiRascunhoNaoEditavelException.class,
                () -> PdiAcessoService.validarRascunho(version, null));
        assertDoesNotThrow(() -> PdiAcessoService.validarRascunho(version, 21L));
        version.setStatusAprovacao(StatusAprovacaoPdi.REPROVADO);
        assertThrows(PdiRascunhoNaoEditavelException.class,
                () -> PdiAcessoService.validarRascunho(version, 21L));
    }

    @Test
    void submissionLocksAndRefreshesUsersInStableUuidOrderBeforeAnyPdiWrite() {
        when(usuarios.buscarGestorId(OWNER)).thenReturn(Optional.of(MANAGER));
        when(usuarios.findByIdForUpdate(MANAGER)).thenReturn(Optional.of(manager));
        when(usuarios.findByIdForUpdate(OWNER)).thenReturn(Optional.of(owner));
        assertSame(owner, acesso.bloquearSubmissao(OWNER));
        var order = inOrder(usuarios, em);
        order.verify(usuarios).buscarGestorId(OWNER);
        order.verify(usuarios).findByIdForUpdate(MANAGER);
        order.verify(em).refresh(manager, LockModeType.PESSIMISTIC_WRITE);
        order.verify(usuarios).findByIdForUpdate(OWNER);
        order.verify(em).refresh(owner, LockModeType.PESSIMISTIC_WRITE);
        verifyNoInteractions(pdis, versoes);
    }

    @Test
    void managerChangeDuringLockDiscoveryIsConflictRatherThanLockingOutOfOrder() {
        when(usuarios.buscarGestorId(OWNER)).thenReturn(Optional.of(MANAGER));
        when(usuarios.findByIdForUpdate(MANAGER)).thenReturn(Optional.of(manager));
        when(usuarios.findByIdForUpdate(OWNER)).thenReturn(Optional.of(owner));
        doNothing().when(em).refresh(manager, LockModeType.PESSIMISTIC_WRITE);
        doAnswer(call -> { owner.setGestor(rh); return null; })
                .when(em).refresh(owner, LockModeType.PESSIMISTIC_WRITE);
        assertStatus(HttpStatus.CONFLICT, () -> acesso.bloquearSubmissao(OWNER));
        assertSame(rh, owner.getGestor());
        var order = inOrder(usuarios, em);
        order.verify(usuarios).buscarGestorId(OWNER);
        order.verify(usuarios).findByIdForUpdate(MANAGER);
        order.verify(em).refresh(manager, LockModeType.PESSIMISTIC_WRITE);
        order.verify(usuarios).findByIdForUpdate(OWNER);
        order.verify(em).refresh(owner, LockModeType.PESSIMISTIC_WRITE);
        verify(usuarios, never()).findByIdForUpdate(RH);
        verifyNoInteractions(pdis, versoes);
    }

    @Test
    void decisionRevalidatesRoleAfterObtainingUserLocks() {
        when(autenticacao.getIfAvailable()).thenReturn(principal);
        when(principal.obterIdUsuarioAutenticado()).thenReturn(MANAGER);
        when(usuarios.findById(MANAGER)).thenReturn(Optional.of(manager));
        when(pdis.buscarTitularId(11L)).thenReturn(Optional.of(OWNER));
        when(usuarios.findByIdForUpdate(MANAGER)).thenReturn(Optional.of(manager));
        when(usuarios.findByIdForUpdate(OWNER)).thenReturn(Optional.of(owner));
        // Identity refresh and locked refresh are distinct EntityManager overloads.
        doNothing().when(em).refresh(manager);
        doAnswer(call -> { manager.setPerfilAcesso(PerfilUsuario.COLABORADOR); return null; })
                .when(em).refresh(manager, LockModeType.PESSIMISTIC_WRITE);
        doNothing().when(em).refresh(owner, LockModeType.PESSIMISTIC_WRITE);
        assertStatus(HttpStatus.FORBIDDEN, () -> acesso.bloquearDecisao(11L));
        assertEquals(PerfilUsuario.COLABORADOR, manager.getPerfilAcesso());
        var order = inOrder(usuarios, pdis, em);
        order.verify(usuarios).findById(MANAGER);
        order.verify(em).refresh(manager);
        order.verify(pdis).buscarTitularId(11L);
        order.verify(usuarios).findByIdForUpdate(MANAGER);
        order.verify(em).refresh(manager, LockModeType.PESSIMISTIC_WRITE);
        order.verify(usuarios).findByIdForUpdate(OWNER);
        order.verify(em).refresh(owner, LockModeType.PESSIMISTIC_WRITE);
        verifyNoInteractions(versoes);
    }

    @Test
    void visibleVersionMustBelongToExactPdiAndAuthorizedActor() {
        when(autenticacao.getIfAvailable()).thenReturn(principal);
        when(principal.obterIdUsuarioAutenticado()).thenReturn(MANAGER);
        when(usuarios.findById(MANAGER)).thenReturn(Optional.of(manager));
        when(versoes.buscarPorId(11L, 21L)).thenReturn(Optional.of(version));
        assertThrows(PdiRascunhoNaoEncontradoException.class, () -> acesso.versaoVisivel(11L, 21L));
        version.setSubmetidaEm(Instant.now());
        assertSame(version, acesso.versaoVisivel(11L, 21L));
        assertThrows(PdiRascunhoNaoEncontradoException.class, () -> acesso.versaoVisivel(12L, 21L));
    }

    private PdiAcessoService policy(String ids) {
        return new PdiAcessoService(usuarios, pdis, versoes, em, autenticacao, ids);
    }

    private static Usuario user(UUID id, PerfilUsuario profile) {
        Usuario user = new Usuario();
        ReflectionTestUtils.setField(user, "id", id);
        user.setPerfilAcesso(profile);
        return user;
    }

    private static void assertStatus(HttpStatus expected, org.junit.jupiter.api.function.Executable operation) {
        assertEquals(expected, assertThrows(ResponseStatusException.class, operation).getStatusCode());
    }
}
