package com.empresa.pdi.integration;

import com.empresa.pdi.dto.AtualizarAcaoPdiRequest;
import com.empresa.pdi.dto.CriarAcaoPdiRequest;
import com.empresa.pdi.dto.CriarPdiRequest;
import com.empresa.pdi.dto.PdiDecisaoRequest;
import com.empresa.pdi.enums.NivelCargo;
import com.empresa.pdi.enums.PerfilUsuario;
import com.empresa.pdi.enums.StatusAprovacaoPdi;
import com.empresa.pdi.enums.StatusExecucaoAcao;
import com.empresa.pdi.enums.StatusExecucaoPdi;
import com.empresa.pdi.model.*;
import com.empresa.pdi.repository.*;
import com.empresa.pdi.security.UsuarioAutenticadoProvider;
import com.empresa.pdi.service.AcaoPdiService;
import com.empresa.pdi.service.PdiAcessoService;
import com.empresa.pdi.service.PdiService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import org.hibernate.SessionFactory;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistry;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.hibernate.engine.jdbc.connections.spi.ConnectionProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.http.HttpStatus;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.DefaultTransactionDefinition;
import org.springframework.web.server.ResponseStatusException;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static com.empresa.pdi.integration.PdiSubmissaoPersistenceIT.transactionalProxy;
import com.empresa.pdi.integration.PdiSubmissaoPersistenceIT.DiagnosticContext;
import com.empresa.pdi.integration.PdiSubmissaoPersistenceIT.DiagnosticStage;
import static org.junit.jupiter.api.Assertions.*;

/**
 * G8 PostgreSQL scenarios. Explicit opt-in is never authorization by itself.
 * Reuses the G7 identity/TLS/preflight boundary; only an outer rollback is permitted.
 * Does not start Boot, migrate, create schema or commit fixture baselines.
 */
@EnabledIfEnvironmentVariable(named = "TALENTSYNK_PDI_DECISAO_IT_ENABLED", matches = "true")
class PdiDecisaoPersistenceIT {
    private static final String ENABLE = "TALENTSYNK_PDI_DECISAO_IT_ENABLED";
    private enum Scenario { APPROVAL, REVISION, REMOVAL, EVENT_FAILURE, COPY_FAILURE }

    @Test void approvalPromotesExactVersionWithoutStartingExecution() { run(Scenario.APPROVAL); }
    @Test void rejectionCopiesIndependentActionsAndAllowsCorrectionAndResubmission() { run(Scenario.REVISION); }
    @Test void removingLastCopiedActionPreservesHistoryAndPreventsSubmission() { run(Scenario.REMOVAL); }
    @Test void decisionEventFailureMarksWholeTransactionForRollback() { run(Scenario.EVENT_FAILURE); }
    @Test void actionCopyFailureMarksWholeTransactionForRollback() { run(Scenario.COPY_FAILURE); }

    private void run(Scenario scenario) {
        DiagnosticContext diagnostic = new DiagnosticContext();
        PdiSubmissaoPersistenceIT.runProtected(ENABLE, "G8-" + scenario.name(), diagnostic,
                connection -> execute(connection, scenario, diagnostic));
    }

    private static void execute(Connection connection, Scenario scenario, DiagnosticContext diagnostic) throws Exception {
        // No automatic GRANT: absence of the existing edit privilege fails the read-only preflight.
        diagnostic.at(DiagnosticStage.ACTION_EDIT_PRIVILEGE);
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT has_table_privilege(current_user, 'public.acao_pdi_versao', 'UPDATE')")) {
            try (ResultSet result = statement.executeQuery()) {
                assertTrue(result.next());
                assertTrue(result.getBoolean(1), "G8 requires action-version edit permission");
            }
        } catch (Exception | AssertionError failure) {
            diagnostic.failed(failure);
            throw failure;
        } finally {
            diagnostic.at(DiagnosticStage.PREFLIGHT_ROLLBACK);
            connection.rollback();
        }
        var ledger = new Ledger();
        StandardServiceRegistry registry = null;
        try {
            diagnostic.at(DiagnosticStage.BOOTSTRAP);
            ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
            StandardServiceRegistryBuilder builder = new StandardServiceRegistryBuilder();
            builder.getSettings().clear();
            builder.addService(ConnectionProvider.class,
                            new PdiSubmissaoPersistenceIT.NoCommitConnectionProvider(connection))
                    .applySetting("hibernate.dialect", "org.hibernate.dialect.PostgreSQLDialect")
                    .applySetting("hibernate.boot.allow_jdbc_metadata_access", false)
                    .applySetting("hibernate.temp.use_jdbc_metadata_defaults", false)
                    .applySetting("hibernate.hbm2ddl.auto", "none")
                    .applySetting("jakarta.persistence.schema-generation.database.action", "none")
                    .applySetting("jakarta.persistence.schema-generation.scripts.action", "none")
                    .applySetting("hibernate.default_schema", "public")
                    .applySetting("hibernate.show_sql", false)
                    .applySetting("hibernate.cache.use_second_level_cache", false)
                    .applySetting("hibernate.cache.use_query_cache", false)
                    .applySetting("hibernate.type.json_format_mapper",
                            new org.hibernate.type.format.jackson.JacksonJsonFormatMapper(mapper));
            registry = builder.build();
            var sources = new MetadataSources(registry);
            Set<Class<?>> entities = Set.of(PDI.class, PdiVersao.class, AcaoPDI.class,
                    AcaoPdiVersao.class, PdiComponente.class, PdiComponenteVersao.class,
                    EntregaSubmissao.class, PdiEvento.class, Usuario.class, Cargo.class, Equipe.class);
            entities.forEach(sources::addAnnotatedClass);
            var metadata = sources.buildMetadata();
            assertEquals(entities, metadata.getEntityBindings().stream().map(binding -> binding.getMappedClass())
                    .collect(java.util.stream.Collectors.toSet()));
            try (SessionFactory factory = metadata.buildSessionFactory()) {
                diagnostic.at(DiagnosticStage.REPOSITORIES);
                connection.setReadOnly(false);
                var tx = new JpaTransactionManager(factory);
                EntityManager em = SharedEntityManagerCreator.createSharedEntityManager(factory);
                var repositories = new org.springframework.data.jpa.repository.support.JpaRepositoryFactory(em);
                UsuarioRepository users = repositories.getRepository(UsuarioRepository.class);
                PDIRepository plans = tracked(PDIRepository.class, repositories.getRepository(PDIRepository.class), ledger);
                PdiVersaoRepository versions = tracked(PdiVersaoRepository.class,
                        repositories.getRepository(PdiVersaoRepository.class), ledger);
                AcaoPDIRepository actions = tracked(AcaoPDIRepository.class,
                        repositories.getRepository(AcaoPDIRepository.class), ledger);
                AcaoPdiVersaoRepository realContents = repositories.getRepository(AcaoPdiVersaoRepository.class);
                PdiEventoRepository realEvents = repositories.getRepository(PdiEventoRepository.class);
                AtomicReference<UUID> identity = new AtomicReference<>();
                AtomicReference<Long> sourceVersion = new AtomicReference<>();
                boolean[] failureObserved = {false};
                var contents = intercept(AcaoPdiVersaoRepository.class, realContents, entity -> {
                    if (scenario == Scenario.COPY_FAILURE && entity instanceof AcaoPdiVersao content
                            && sourceVersion.get() != null && !sourceVersion.get().equals(content.getPdiVersaoId())) {
                        failureObserved[0] = true;
                        throw new IllegalStateException("Controlled G8 copy failure");
                    }
                }, ledger);
                var events = intercept(PdiEventoRepository.class, realEvents, entity -> {
                    if (scenario == Scenario.EVENT_FAILURE && entity instanceof PdiEvento event
                            && "PDI_REPROVADO".equals(event.getTipo())) {
                        failureObserved[0] = true;
                        // A real PostgreSQL CHECK failure affects only the newly created fixture event.
                        org.springframework.test.util.ReflectionTestUtils.setField(event, "tipo", "");
                    }
                }, ledger);
                var beans = new DefaultListableBeanFactory();
                beans.registerSingleton("g8Identity", (UsuarioAutenticadoProvider) identity::get);
                var provider = beans.getBeanProvider(UsuarioAutenticadoProvider.class);
                var access = new PdiAcessoService(users, plans, versions, em, provider, "");
                var service = transactionalProxy(new PdiService(plans, versions, contents, events,
                        users, provider, mapper, actions, access), tx);
                var actionService = transactionalProxy(new AcaoPdiService(actions, contents, plans,
                        versions, events, users, provider, mapper, access), tx);
                diagnostic.at(DiagnosticStage.BEGIN_TRANSACTION);
                TransactionStatus outer = tx.getTransaction(new DefaultTransactionDefinition());
                try {
                    diagnostic.at(DiagnosticStage.CREATE_FIXTURES);
                    Fixture fixture = createFixture(em, ledger);
                    identity.set(fixture.ownerId());
                    diagnostic.at(DiagnosticStage.CREATE_DRAFT);
                    var draft = service.criar(new CriarPdiRequest("IT G8 plan", "Planned development",
                            LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31)));
                    diagnostic.at(DiagnosticStage.CREATE_ACTION);
                    var original = actionService.criar(draft.id(), actionRequest());
                    sourceVersion.set(draft.versaoId());
                    diagnostic.at(DiagnosticStage.SUBMIT_INITIAL);
                    service.submeter(draft.id());
                    identity.set(fixture.managerId());
                    if (scenario == Scenario.APPROVAL) {
                        diagnostic.at(DiagnosticStage.APPROVE_INITIAL);
                        service.aprovar(draft.id(), draft.versaoId(), new PdiDecisaoRequest(null));
                        diagnostic.at(DiagnosticStage.FLUSH_AND_CLEAR);
                        em.flush();
                        em.clear();
                        diagnostic.at(DiagnosticStage.ASSERT_APPROVAL);
                        assertApproved(em, draft.id(), draft.versaoId(), fixture.managerId());
                        diagnostic.at(DiagnosticStage.ASSERT_ACTION_NOT_STARTED);
                        assertActionNotStarted(em.find(AcaoPDI.class, original.id()));
                        diagnostic.at(DiagnosticStage.ASSERT_DECISION_EVENT);
                        assertDecisionEvent(em, draft.id(), draft.versaoId(), fixture.managerId(), "PDI_APROVADO");
                    } else if (scenario == Scenario.EVENT_FAILURE || scenario == Scenario.COPY_FAILURE) {
                        diagnostic.at(DiagnosticStage.EXPECTED_FAILURE_ASSERTIONS);
                        RuntimeException failure = assertThrows(RuntimeException.class, () -> service.reprovar(draft.id(),
                                draft.versaoId(), new PdiDecisaoRequest("Corrigir o planejamento")));
                        assertTrue(failureObserved[0], "Failure must occur at the selected write boundary");
                        if (scenario == Scenario.EVENT_FAILURE) {
                            assertTrue(hasSqlState(failure, "23514"), "Expected PostgreSQL CHECK violation");
                        }
                        assertTrue(outer.isRollbackOnly(), "Transactional proxy must prevent a partial commit");
                    } else {
                        diagnostic.at(DiagnosticStage.REJECT_VERSION);
                        var decision = service.reprovar(draft.id(), draft.versaoId(),
                                new PdiDecisaoRequest("Corrigir o planejamento"));
                        Long revisionId = decision.novaVersaoId();
                        assertNotNull(revisionId);
                        diagnostic.at(DiagnosticStage.FLUSH_AND_CLEAR);
                        em.flush();
                        em.clear();
                        diagnostic.at(DiagnosticStage.LOAD_REJECTED_VERSION);
                        var rejected = em.find(PdiVersao.class, draft.versaoId());
                        diagnostic.at(DiagnosticStage.LOAD_REVISION);
                        var revision = em.find(PdiVersao.class, revisionId);
                        diagnostic.at(DiagnosticStage.ASSERT_REVISION_CONTENT);
                        assertEquals(StatusAprovacaoPdi.REPROVADO, rejected.getStatusAprovacao());
                        assertEquals("Corrigir o planejamento", rejected.getJustificativaDecisao());
                        assertEquals(fixture.managerId(), rejected.getDecididoPor().getId());
                        assertNotNull(rejected.getDecididaEm());
                        assertEquals(2, revision.getNumero());
                        assertEquals(StatusAprovacaoPdi.RASCUNHO, revision.getStatusAprovacao());
                        assertNull(revision.getSubmetidaEm());
                        assertNull(revision.getGestorNaSubmissao());
                        assertNull(revision.getDecididaEm());
                        assertNull(revision.getDecididoPor());
                        assertNull(revision.getJustificativaDecisao());
                        assertEquals(rejected.getTitulo(), revision.getTitulo());
                        assertEquals(rejected.getObjetivo(), revision.getObjetivo());
                        assertEquals(rejected.getInicioPlanejado(), revision.getInicioPlanejado());
                        assertEquals(rejected.getFimPlanejado(), revision.getFimPlanejado());
                        assertNull(em.find(PDI.class, draft.id()).getVersaoVigenteId());
                        diagnostic.at(DiagnosticStage.ASSERT_DECISION_EVENT);
                        assertDecisionEvent(em, draft.id(), draft.versaoId(), fixture.managerId(), "PDI_REPROVADO");
                        identity.set(fixture.ownerId());
                        diagnostic.at(DiagnosticStage.LIST_COPIED_ACTIONS);
                        var copied = actionService.listarNaVersao(draft.id(), revisionId);
                        diagnostic.at(DiagnosticStage.ASSERT_ACTION_COPY);
                        assertEquals(1, copied.size());
                        var copy = copied.get(0);
                        assertNotEquals(original.id(), copy.id());
                        assertNotEquals(original.acaoVersaoId(), copy.acaoVersaoId());
                        assertEquals(revisionId, copy.pdiVersaoId());
                        assertEquals(original.titulo(), copy.titulo());
                        assertEquals(original.objetivo(), copy.objetivo());
                        assertEquals(original.resultadoEsperado(), copy.resultadoEsperado());
                        assertEquals(original.inicioPlanejado(), copy.inicioPlanejado());
                        assertEquals(original.fimPlanejado(), copy.fimPlanejado());
                        assertEquals(original.ordem(), copy.ordem());
                        diagnostic.at(DiagnosticStage.LOAD_REVISION_EVENT);
                        var revisionEvent = em.createQuery("select e from PdiEvento e where e.pdiId = :pdi "
                                        + "and e.pdiVersaoId = :version and e.tipo = 'REVISAO_CRIADA'", PdiEvento.class)
                                .setParameter("pdi", draft.id()).setParameter("version", revisionId).getSingleResult();
                        diagnostic.at(DiagnosticStage.ASSERT_COPY_MAPPING);
                        assertEquals(draft.versaoId().longValue(), revisionEvent.getDados().path("versaoOrigemId").asLong());
                        assertEquals(revisionId.longValue(), revisionEvent.getDados().path("versaoDestinoId").asLong());
                        var mapping = revisionEvent.getDados().path("acoesCopiadas");
                        assertEquals(1, mapping.size());
                        assertEquals(original.id().longValue(), mapping.get(0).path("acaoOrigemId").asLong());
                        assertEquals(original.acaoVersaoId().longValue(), mapping.get(0).path("acaoVersaoOrigemId").asLong());
                        assertEquals(copy.id().longValue(), mapping.get(0).path("acaoDestinoId").asLong());
                        assertEquals(copy.acaoVersaoId().longValue(), mapping.get(0).path("acaoVersaoDestinoId").asLong());
                        diagnostic.at(DiagnosticStage.ASSERT_ACTION_NOT_STARTED);
                        assertActionNotStarted(em.find(AcaoPDI.class, copy.id()));
                        diagnostic.at(DiagnosticStage.COUNT_COPIED_COMPONENTS);
                        assertEquals(0L, em.createQuery("select count(c) from PdiComponente c "
                                        + "where c.acaoPdi.id = :id", Long.class)
                                .setParameter("id", copy.id()).getSingleResult());
                        if (scenario == Scenario.REMOVAL) {
                            diagnostic.at(DiagnosticStage.REMOVE_ACTION);
                            actionService.remover(draft.id(), copy.id(), revisionId);
                            diagnostic.at(DiagnosticStage.LIST_REMAINING_ACTIONS);
                            assertTrue(actionService.listarNaVersao(draft.id(), revisionId).isEmpty());
                            diagnostic.at(DiagnosticStage.ASSERT_REMOVAL);
                            assertNotNull(em.find(AcaoPdiVersao.class, copy.acaoVersaoId()).getRemovidaEm());
                            assertNull(em.find(AcaoPdiVersao.class, original.acaoVersaoId()).getRemovidaEm());
                            diagnostic.at(DiagnosticStage.COUNT_REMOVAL_EVENTS);
                            assertEquals(1L, em.createQuery("select count(e) from PdiEvento e "
                                            + "where e.pdiId = :pdi and e.pdiVersaoId = :version "
                                            + "and e.tipo = 'ACAO_REMOVIDA'", Long.class)
                                    .setParameter("pdi", draft.id()).setParameter("version", revisionId).getSingleResult());
                            diagnostic.at(DiagnosticStage.ASSERT_EMPTY_SUBMISSION_REJECTED);
                            ResponseStatusException failure = assertThrows(ResponseStatusException.class,
                                    () -> service.submeter(draft.id(), revisionId));
                            assertEquals(HttpStatus.CONFLICT, failure.getStatusCode());
                        } else {
                            diagnostic.at(DiagnosticStage.EDIT_COPIED_ACTION);
                            actionService.atualizarRascunho(draft.id(), copy.id(), new AtualizarAcaoPdiRequest(
                                    "Revised action", copy.objetivo(), copy.inicioPlanejado(), copy.fimPlanejado(),
                                    "Revised expected result"), revisionId);
                            diagnostic.at(DiagnosticStage.FLUSH_AND_CLEAR);
                            em.flush();
                            em.clear();
                            diagnostic.at(DiagnosticStage.ASSERT_ORIGINAL_PRESERVED);
                            assertEquals(original.titulo(), em.find(AcaoPdiVersao.class,
                                    original.acaoVersaoId()).getTitulo());
                            assertEquals(StatusAprovacaoPdi.REPROVADO,
                                    em.find(PdiVersao.class, draft.versaoId()).getStatusAprovacao());
                            diagnostic.at(DiagnosticStage.RESUBMIT_REVISION);
                            service.submeter(draft.id(), revisionId);
                            identity.set(fixture.managerId());
                            diagnostic.at(DiagnosticStage.APPROVE_REVISION);
                            service.aprovar(draft.id(), revisionId, new PdiDecisaoRequest("Planejamento ajustado"));
                            diagnostic.at(DiagnosticStage.FLUSH_AND_CLEAR);
                            em.flush();
                            em.clear();
                            diagnostic.at(DiagnosticStage.ASSERT_APPROVAL);
                            assertApproved(em, draft.id(), revisionId, fixture.managerId());
                            assertEquals(StatusAprovacaoPdi.REPROVADO,
                                    em.find(PdiVersao.class, draft.versaoId()).getStatusAprovacao());
                            assertEquals(original.titulo(), em.find(AcaoPdiVersao.class,
                                    original.acaoVersaoId()).getTitulo());
                            assertActionNotStarted(em.find(AcaoPDI.class, original.id()));
                            assertActionNotStarted(em.find(AcaoPDI.class, copy.id()));
                        }
                    }
                } catch (Exception | AssertionError failure) {
                    // Freeze the failing operation before finally moves to rollback/cleanup.
                    diagnostic.failed(failure);
                    throw failure;
                } finally {
                    diagnostic.at(DiagnosticStage.ROLLBACK_FIXTURES);
                    tx.rollback(outer);
                }
                diagnostic.at(DiagnosticStage.ASSERT_FIXTURE_ABSENCE);
                connection.setReadOnly(true);
                try {
                    ledger.assertAbsent(connection);
                    System.out.println("G8_FIXTURE_ABSENCE_VERIFIED case=" + scenario.name()
                            + " scope=current-fixture-ids-and-pdi");
                } catch (Exception | AssertionError failure) {
                    diagnostic.failed(failure);
                    throw failure;
                } finally {
                    diagnostic.at(DiagnosticStage.READ_ONLY_VERIFICATION_ROLLBACK);
                    connection.rollback();
                }
                diagnostic.at(DiagnosticStage.CLOSE_FACTORY);
            } catch (Exception | AssertionError failure) {
                diagnostic.failed(failure);
                throw failure;
            } finally {
                diagnostic.at(DiagnosticStage.PHYSICAL_ROLLBACK);
                connection.rollback();
            }
        } catch (Exception | AssertionError failure) {
            diagnostic.failed(failure);
            throw failure;
        } finally {
            diagnostic.at(DiagnosticStage.DESTROY_REGISTRY);
            if (registry != null) StandardServiceRegistryBuilder.destroy(registry);
        }
    }

    private static CriarAcaoPdiRequest actionRequest() {
        return new CriarAcaoPdiRequest("IT G8 action", "Objective", "Expected result",
                LocalDate.of(2026, 2, 1), LocalDate.of(2026, 11, 30));
    }

    private static Fixture createFixture(EntityManager em, Ledger ledger) {
        String suffix = UUID.randomUUID().toString();
        Cargo cargo = new Cargo();
        cargo.setNome("IT G8 cargo " + suffix);
        cargo.setNivel(NivelCargo.NAO_APLICAVEL);
        em.persist(cargo);
        Equipe team = new Equipe();
        team.setNome("IT G8 team " + suffix);
        em.persist(team);
        Usuario manager = new Usuario();
        manager.setNome("IT G8 manager " + suffix);
        manager.setEmail("g8-manager-" + suffix + "@example.invalid");
        manager.setPerfilAcesso(PerfilUsuario.GESTOR);
        manager.setCargo(cargo);
        manager.setEquipeAtual(team);
        em.persist(manager);
        Usuario owner = new Usuario();
        owner.setNome("IT G8 owner " + suffix);
        owner.setEmail("g8-owner-" + suffix + "@example.invalid");
        owner.setPerfilAcesso(PerfilUsuario.COLABORADOR);
        owner.setCargo(cargo);
        owner.setEquipeAtual(team);
        owner.setGestor(manager);
        em.persist(owner);
        em.flush();
        ledger.add("cargo", cargo.getId());
        ledger.add("equipe", team.getId());
        ledger.add("usuario", manager.getId());
        ledger.add("usuario", owner.getId());
        return new Fixture(owner.getId(), manager.getId());
    }

    private static void assertApproved(EntityManager em, Long pdiId, Long versionId, UUID managerId) {
        PDI pdi = em.find(PDI.class, pdiId);
        PdiVersao version = em.find(PdiVersao.class, versionId);
        assertEquals(versionId, pdi.getVersaoVigenteId());
        assertEquals(StatusExecucaoPdi.NAO_INICIADO, pdi.getStatusExecucao());
        assertNull(pdi.getIniciadoEm());
        assertNull(pdi.getConcluidoEm());
        assertNull(pdi.getConcluidoPor());
        assertEquals(StatusAprovacaoPdi.APROVADO, version.getStatusAprovacao());
        assertEquals(managerId, version.getDecididoPor().getId());
        assertNotNull(version.getDecididaEm());
    }

    private static void assertActionNotStarted(AcaoPDI action) {
        assertNotNull(action);
        assertEquals(StatusExecucaoAcao.NAO_INICIADA, action.getStatusExecucao());
        assertNull(action.getIniciadaEm());
        assertNull(action.getConcluidaEm());
        assertNull(action.getConcluidaPor());
    }

    private static void assertDecisionEvent(EntityManager em, Long pdiId, Long versionId,
                                            UUID managerId, String type) {
        var decisions = em.createQuery("select e from PdiEvento e where e.pdiId = :pdi "
                        + "and e.pdiVersaoId = :version and e.tipo = :type", PdiEvento.class)
                .setParameter("pdi", pdiId).setParameter("version", versionId).setParameter("type", type)
                .getResultList();
        assertEquals(1, decisions.size());
        assertEquals(managerId, decisions.get(0).getAutor().getId());
        assertNotNull(decisions.get(0).getOcorridoEm());
    }

    private record Fixture(UUID ownerId, UUID managerId) { }

    private static boolean hasSqlState(Throwable failure, String expected) {
        Set<Throwable> seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        for (Throwable current = failure; current != null && seen.add(current); current = current.getCause()) {
            if (current instanceof SQLException sqlFailure && expected.equals(sqlFailure.getSQLState())) return true;
        }
        return false;
    }

    @FunctionalInterface private interface BeforeWrite { void accept(Object entity); }

    private static <T> T tracked(Class<T> type, T delegate, Ledger ledger) {
        return intercept(type, delegate, ignored -> { }, ledger);
    }

    private static <T> T intercept(Class<T> type, T delegate, BeforeWrite before, Ledger ledger) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (proxy, method, args) -> {
            if ("saveAndFlush".equals(method.getName())) before.accept(args[0]);
            try {
                Object result = method.invoke(delegate, args);
                if ("saveAndFlush".equals(method.getName())) ledger.capture(result);
                return result;
            } catch (InvocationTargetException failure) {
                throw failure.getCause();
            }
        }));
    }

    private static final class Ledger {
        private static final Set<String> TABLES = Set.of("cargo", "equipe", "usuario", "pdi", "pdi_versao",
                "acao_pdi", "acao_pdi_versao", "pdi_evento");
        private final Map<String, Set<Object>> rows = new LinkedHashMap<>();

        void add(String table, Object id) {
            if (!TABLES.contains(table) || id == null) throw new IllegalArgumentException("Invalid fixture reference");
            rows.computeIfAbsent(table, ignored -> new LinkedHashSet<>()).add(id);
        }

        void capture(Object entity) {
            if (entity instanceof PDI pdi) add("pdi", pdi.getId());
            else if (entity instanceof PdiVersao version) add("pdi_versao", version.getId());
            else if (entity instanceof AcaoPDI action) add("acao_pdi", action.getId());
            else if (entity instanceof AcaoPdiVersao content) add("acao_pdi_versao", content.getId());
            else if (entity instanceof PdiEvento event) add("pdi_evento", event.getId());
        }

        void assertAbsent(Connection connection) throws SQLException {
            for (var table : rows.entrySet()) {
                try (PreparedStatement statement = connection.prepareStatement(
                        "SELECT EXISTS (SELECT 1 FROM public." + table.getKey() + " WHERE id = ?)")) {
                    for (Object id : table.getValue()) {
                        statement.setObject(1, id);
                        try (ResultSet result = statement.executeQuery()) {
                            assertTrue(result.next());
                            assertFalse(result.getBoolean(1), "A G8 fixture must not survive rollback");
                        }
                    }
                }
            }
            // Covers events/content allocated immediately before an injected write failure as well.
            for (Object pdiId : rows.getOrDefault("pdi", Set.of())) {
                for (String table : Set.of("pdi_versao", "acao_pdi", "acao_pdi_versao", "pdi_evento")) {
                    try (PreparedStatement statement = connection.prepareStatement(
                            "SELECT COUNT(*) FROM public." + table + " WHERE pdi_id = ?")) {
                        statement.setObject(1, pdiId);
                        try (ResultSet result = statement.executeQuery()) {
                            assertTrue(result.next());
                            assertEquals(0L, result.getLong(1), "Aggregate rows must not survive rollback");
                        }
                    }
                }
            }
        }
    }
}
