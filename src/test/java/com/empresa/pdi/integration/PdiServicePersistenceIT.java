package com.empresa.pdi.integration;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import com.empresa.pdi.enums.NivelCargo;
import com.empresa.pdi.enums.PerfilUsuario;
import com.empresa.pdi.enums.StatusAprovacaoPdi;
import com.empresa.pdi.enums.StatusExecucaoPdi;
import com.empresa.pdi.enums.StatusExecucaoAcao;
import com.empresa.pdi.model.*;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.Transaction;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistry;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.hibernate.engine.jdbc.connections.spi.ConnectionProvider;
import org.junit.jupiter.api.Test;
import org.opentest4j.AssertionFailedError;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.slf4j.LoggerFactory;
import org.postgresql.util.PSQLException;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.zip.CRC32;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Explicit-only service persistence tests; guards intentionally copied from PdiPersistenceIT
 * to keep the previously approved test unchanged. No Spring Boot or migrations.
 * The original six tests and four action-edit tests always roll back their fixtures.
 * Only the separately gated T0/T1/T2 test may commit its new baseline fixtures once.
 */
@EnabledIfEnvironmentVariable(named = "TALENTSYNK_PDI_SERVICE_PERSISTENCE_ENABLED", matches = "true")
class PdiServicePersistenceIT {
    private static final String PREFIX = "TALENTSYNK_PDI_SERVICE_IT_";
    private static final String APPROVED_BRANCH = "test-pdi-v2";

    @Test
    void createsDraftVersionAndCreationEvent() {
        executeControlledScenario(false);
    }

    @Test
    void eventCheckFailureRollsBackPlanAndVersion() {
        executeControlledScenario(true);
    }

    @Test
    void editsInitialDraftAndPreservesHistory() {
        executeControlledScenario(false, true);
    }

    @Test
    void editEventCheckFailureRollsBackDraftChanges() {
        executeControlledScenario(true, true);
    }

    @Test
    void createsActionWithVersionAndAdditionEvent() {
        executeControlledScenario(false, false, true);
    }

    @Test
    void actionEventCheckFailureRollsBackActionAndContent() {
        executeControlledScenario(true, false, true);
    }

    @Test
    void editsActionContentAndPreservesHistory() {
        executeActionEditScenario(ActionEditMode.EDIT);
    }

    @Test
    void clearsActionContentWithNulls() {
        executeActionEditScenario(ActionEditMode.NULLS);
    }

    @Test
    void unchangedActionDoesNotWriteOrAppendEvent() {
        executeActionEditScenario(ActionEditMode.UNCHANGED);
    }

    @Test
    void invalidActionDatesDoNotChangePersistedState() {
        executeActionEditScenario(ActionEditMode.INVALID_DATES);
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "TALENTSYNK_PDI_ACTION_EDIT_BASELINE_COMMIT_ENABLED", matches = "true")
    void actionEditEventFailureRestoresCommittedBaseline() {
        executeActionEditScenario(ActionEditMode.ROLLBACK);
    }

    private void executeControlledScenario(boolean failEvent) {
        executeControlledScenario(failEvent, false);
    }

    private void executeControlledScenario(boolean failEvent, boolean editDraft) {
        executeControlledScenario(failEvent, editDraft, false);
    }

    private void executeControlledScenario(boolean failEvent, boolean editDraft, boolean addAction) {
        org.junit.jupiter.api.Assumptions.assumeTrue(
                "true".equals(System.getenv("TALENTSYNK_PDI_SERVICE_PERSISTENCE_ENABLED")),
                "PDI persistence test requires explicit enablement."
        );
        // Keep SQL errors, connection settings and possible payloads out of test reports.
        Logger hibernate = (Logger) LoggerFactory.getLogger("org.hibernate");
        Level previousLevel = hibernate.getLevel();
        java.util.logging.Logger jdbc = java.util.logging.Logger.getLogger("org.postgresql");
        java.util.logging.Level previousJdbc = jdbc.getLevel();
        String stage = "external configuration and Neon identity";
        String[] capturedEventDiagnostic = {null};
        try {
            hibernate.setLevel(Level.OFF);
            jdbc.setLevel(java.util.logging.Level.OFF);
            Config config = configuration();
            verifyNeonIdentity(config);
            Properties properties = new Properties();
            properties.setProperty("user", config.user());
            properties.setProperty("password", config.password());
            properties.setProperty("sslmode", "verify-full");
            properties.setProperty(
                    "sslfactory",
                    "org.postgresql.ssl.DefaultJavaSSLFactory"
            );
            properties.setProperty("connectTimeout", "15");
            properties.setProperty("socketTimeout", "30");
            properties.setProperty("readOnlyMode", "transaction");
            properties.setProperty("options", "-c search_path=public -c statement_timeout=15000 -c lock_timeout=5000");
            stage = "read-only database preflight";
            try (Connection connection = DriverManager.getConnection(config.url(), properties)) {
                connection.setReadOnly(true);
                connection.setAutoCommit(false);
                try {
                    verifyDatabase(connection, config);
                    verifyMigrations(connection);
                } finally {
                    connection.rollback();
                }
                // A second independent API check immediately before enabling writes.
                stage = "Neon identity recheck";
                verifyNeonIdentity(config);
                stage = "Hibernate persistence and rollback";
                runScenario(connection, failEvent, editDraft, addAction, capturedEventDiagnostic);
            }
        } catch (Exception | AssertionError failure) {
            // Do not attach raw exceptions: JDBC/Hibernate messages may contain secrets/data.
            throw new AssertionError("PDI persistence test failed at: " + stage
                    + "; category=" + failure.getClass().getSimpleName()
                    + "; " + assertionDiagnostic(failure)
                    + "; " + postgresDiagnostic(failure, false)
                    + (capturedEventDiagnostic[0] == null ? "" : "; capturedEventFailure={" + capturedEventDiagnostic[0] + "}"),
                    sanitizedCauseChain(failure));
        } finally {
            hibernate.setLevel(previousLevel);
            jdbc.setLevel(previousJdbc);
        }
    }

    // Only fixed assertion labels and explicitly safe scalar values enter reports.
    private static String assertionDiagnostic(Throwable failure) {
        Set<String> identifiers = Set.of(
                "evento.persistenciaInvocada",
                "pdi.persistido",
                "versao.persistida",
                "pdi.titular",
                "pdi.statusExecucao",
                "pdi.versaoVigenteId",
                "pdi.versaoVigente",
                "versao.numero",
                "versao.statusAprovacao",
                "versao.pdi",
                "versao.titulo",
                "versao.objetivo",
                "versao.inicioPlanejado",
                "versao.fimPlanejado",
                "versao.submetidaEm",
                "evento.quantidade",
                "evento.tipo",
                "evento.autor",
                "evento.pdiId",
                "evento.pdiVersaoId",
                "evento.pdiVersao",
                "evento.acaoPdiId",
                "evento.componenteId",
                "evento.submissao",
                "evento.ocorridoEm",
                "evento.dados",
                "evento.snapshot",
                "bootstrap.entidades",
                "acao.conteudo.acao",
                "acao.conteudo.acaoId",
                "acao.conteudo.fim",
                "acao.conteudo.id",
                "acao.conteudo.inicio",
                "acao.conteudo.objetivo",
                "acao.conteudo.ordem",
                "acao.conteudo.pdiId",
                "acao.conteudo.pdiVersaoId",
                "acao.conteudo.persistido",
                "acao.conteudo.resultado",
                "acao.conteudo.titulo",
                "acao.conteudo.versao",
                "acao.criacao.autorPreservado",
                "acao.criacao.dadosPreservados",
                "acao.criacao.semAcao",
                "acao.criacao.semSnapshot",
                "acao.criacao.tipoPreservado",
                "acao.evento.acao",
                "acao.evento.acaoId",
                "acao.evento.autor",
                "acao.evento.conteudo",
                "acao.evento.dados",
                "acao.evento.data",
                "acao.evento.pdiId",
                "acao.evento.pdiVersaoId",
                "acao.evento.quantidade",
                "acao.evento.semComponente",
                "acao.evento.semSnapshot",
                "acao.evento.semSubmissao",
                "acao.evento.versao",
                "acao.eventoInvocado",
                "acao.falha.constraint",
                "acao.falha.conteudoInserido",
                "acao.falha.eventoInvocado",
                "acao.falha.excecao",
                "acao.falha.identidadeInserida",
                "acao.falha.rollbackOnly",
                "acao.falha.sqlstate",
                "acao.historico.quantidade",
                "acao.identidade.concluidaPor",
                "acao.identidade.conclusao",
                "acao.identidade.estado",
                "acao.identidade.id",
                "acao.identidade.inicio",
                "acao.identidade.pdi",
                "acao.identidade.persistida",
                "acao.identidade.titular",
                "acao.pdi.estadoPreservado",
                "acao.pdi.fimPreservado",
                "acao.pdi.inicioPreservado",
                "acao.pdi.numeroPreservado",
                "acao.pdi.objetivoPreservado",
                "acao.pdi.rascunhoPreservado",
                "acao.pdi.semSubmissao",
                "acao.pdi.semVersaoVigente",
                "acao.pdi.titularPreservado",
                "acao.pdi.tituloPreservado",
                "acao.response.pdiId",
                "acao.response.versaoId",
                "acao.rollback.consultaEventos",
                "acao.rollback.eventosAusentes",
                "rollback.acao_pdi.resultado",
                "rollback.acao_pdi.ausencia",
                "rollback.acao_pdi_versao.resultado",
                "rollback.acao_pdi_versao.ausencia",

                "edicao.criacao.dados",
                "edicao.criacao.snapshot",
                "edicao.criacao.tipo",
                "edicao.evento.acao",
                "edicao.evento.autor",
                "edicao.evento.componente",
                "edicao.evento.dados",
                "edicao.evento.ocorridoEm",
                "edicao.evento.pdiId",
                "edicao.evento.quantidade",
                "edicao.evento.snapshot",
                "edicao.evento.submissao",
                "edicao.evento.versao",
                "edicao.evento.versaoId",
                "edicao.eventoInvocado",
                "edicao.falha.constraint",
                "edicao.falha.eventoInvocado",
                "edicao.falha.excecao",
                "edicao.falha.rollbackOnly",
                "edicao.falha.sqlstate",
                "edicao.flush.fim",
                "edicao.flush.inicio",
                "edicao.flush.objetivo",
                "edicao.flush.titulo",
                "edicao.historico.quantidade",
                "edicao.pdi.id",
                "edicao.pdi.persistido",
                "edicao.pdi.status",
                "edicao.pdi.titular",
                "edicao.pdi.versaoVigente",
                "edicao.pdi.versaoVigenteId",
                "edicao.response.conteudo",
                "edicao.response.id",
                "edicao.response.versaoId",
                "edicao.versao.fim",
                "edicao.versao.id",
                "edicao.versao.inicio",
                "edicao.versao.numero",
                "edicao.versao.objetivo",
                "edicao.versao.pdi",
                "edicao.versao.persistida",
                "edicao.versao.status",
                "edicao.versao.submetidaEm",
                "edicao.versao.titulo",

                "rollback.cargo.resultado",
                "rollback.cargo.ausencia",
                "rollback.equipe.resultado",
                "rollback.equipe.ausencia",
                "rollback.usuario.resultado",
                "rollback.usuario.ausencia",
                "rollback.pdi.resultado",
                "rollback.pdi.ausencia",
                "rollback.pdi_versao.resultado",
                "rollback.pdi_versao.ausencia",
                "rollback.pdi_evento.resultado",
                "rollback.pdi_evento.ausencia");
        Set<Throwable> seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        for (Throwable current = failure; current != null && seen.add(current); current = current.getCause()) {
            if (!(current instanceof AssertionFailedError assertion)) continue;
            String message = assertion.getMessage();
            String identifier = message == null ? "UNAVAILABLE" : message.split(" ==> ", 2)[0];
            if (!identifiers.contains(identifier)) identifier = "UNAVAILABLE";
            int line = -1;
            for (StackTraceElement frame : assertion.getStackTrace()) {
                if (PdiServicePersistenceIT.class.getName().equals(frame.getClassName())) {
                    line = frame.getLineNumber();
                    break;
                }
            }
            String values = "values=UNAVAILABLE";
            if (assertion.isExpectedDefined() && assertion.isActualDefined()) {
                Object expected = assertion.getExpected().getValue();
                Object actual = assertion.getActual().getValue();
                values = "expected=" + safeAssertionValue(expected) + "; actual=" + safeAssertionValue(actual)
                        + "; comparison=" + (expected == null || actual == null ? "NULL_MISMATCH"
                        : expected.getClass() != actual.getClass() ? "DIFFERENT_TYPES" : "DIFFERENT_VALUES");
            }
            return "assertion=" + identifier + "; line=" + (line < 0 ? "UNAVAILABLE" : line) + "; " + values;
        }
        return "assertion=UNAVAILABLE";
    }

    private static String safeAssertionValue(Object value) {
        if (value == null) return "NULL";
        if (value instanceof Boolean || value instanceof Integer
                || value instanceof StatusExecucaoPdi || value instanceof StatusAprovacaoPdi
                || value instanceof StatusExecucaoAcao) {
            return value.toString();
        }
        if (value instanceof JsonNode node) return "JSON_" + node.getNodeType().name() + "[REDACTED]";
        if (value instanceof UUID) return "UUID[REDACTED]";
        return "NON_NULL[REDACTED]";
    }

    private static Throwable sanitizedCauseChain(Throwable failure) {
        var seen = java.util.Collections.newSetFromMap(
                new java.util.IdentityHashMap<Throwable, Boolean>());
        var types = new java.util.ArrayList<String>();

        Throwable current = failure;
        while (current != null && seen.add(current) && types.size() < 32) {
            types.add(current.getClass().getName());
            current = current.getCause();
        }

        Throwable sanitized = null;
        if (current != null) {
            sanitized = new Exception("Cause chain truncated");
            sanitized.setStackTrace(new StackTraceElement[0]);
        }

        for (int i = types.size() - 1; i >= 0; i--) {
            var node = new Exception(types.get(i), sanitized);
            node.setStackTrace(new StackTraceElement[0]);
            sanitized = node;
        }

        return sanitized;
    }


    private static void runScenario(Connection connection, boolean failEvent, boolean editDraft, boolean addAction, String[] capturedEventDiagnostic) throws Exception {
        StandardServiceRegistry registry = null;
        java.util.Map<String, Object> ids = new java.util.LinkedHashMap<>();
        Set<Long> eventIds = new java.util.LinkedHashSet<>();
        try {
            StandardServiceRegistryBuilder builder = new StandardServiceRegistryBuilder();
            builder.getSettings().clear();
            // Test-local JSON reader: preserve every fractional digit from PostgreSQL.
            var jsonMapper = new ObjectMapper().findAndRegisterModules()
                    .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
            builder.applySetting("hibernate.type.json_format_mapper",
                    new org.hibernate.type.format.jackson.JacksonJsonFormatMapper(jsonMapper));
            builder.addService(ConnectionProvider.class, new SingleConnectionProvider(connection))
                    .applySetting("hibernate.dialect", "org.hibernate.dialect.PostgreSQLDialect")
                    .applySetting("hibernate.boot.allow_jdbc_metadata_access", false)
                    .applySetting("hibernate.temp.use_jdbc_metadata_defaults", false)
                    .applySetting("hibernate.hbm2ddl.auto", "none")
                    .applySetting("jakarta.persistence.schema-generation.database.action", "none")
                    .applySetting("jakarta.persistence.schema-generation.scripts.action", "none")
                    .applySetting("hibernate.default_schema", "public")
                    .applySetting("hibernate.show_sql", false)
                    .applySetting("hibernate.cache.use_second_level_cache", false)
                    .applySetting("hibernate.cache.use_query_cache", false);
            registry = builder.build();
            var sources = new MetadataSources(registry);
            Set<Class<?>> expected = Set.of(PDI.class, PdiVersao.class, AcaoPDI.class, AcaoPdiVersao.class,
                    PdiComponente.class, PdiComponenteVersao.class, EntregaSubmissao.class,
                    PdiEvento.class, Usuario.class, Cargo.class, Equipe.class);
            expected.forEach(sources::addAnnotatedClass);
            var metadata = sources.buildMetadata();
            assertEquals(expected, metadata.getEntityBindings().stream()
                    .map(binding -> binding.getMappedClass()).collect(Collectors.toSet()), "bootstrap.entidades");
            try (SessionFactory factory = metadata.buildSessionFactory()) {
                connection.setReadOnly(false);
                var manager = new org.springframework.orm.jpa.JpaTransactionManager(factory);
                var em = org.springframework.orm.jpa.SharedEntityManagerCreator.createSharedEntityManager(factory);
                var repositories = new org.springframework.data.jpa.repository.support.JpaRepositoryFactory(em);
                var users = repositories.getRepository(com.empresa.pdi.repository.UsuarioRepository.class);
                var plans = repositories.getRepository(com.empresa.pdi.repository.PDIRepository.class);
                var versions = repositories.getRepository(com.empresa.pdi.repository.PdiVersaoRepository.class);
                var realEvents = repositories.getRepository(com.empresa.pdi.repository.PdiEventoRepository.class);
                var eventCalled = new java.util.concurrent.atomic.AtomicBoolean();
                com.empresa.pdi.repository.PdiEventoRepository eventWriter =
                        new com.empresa.pdi.repository.PdiEventoRepository() {
                    @Override
                    public <S extends PdiEvento> S saveAndFlush(S event) {
                        eventCalled.set(true);
                        ids.put("pdi", event.getPdiId());
                        ids.put("pdi_versao", event.getPdiVersaoId());
                        if (addAction && "ACAO_ADICIONADA".equals(event.getTipo())) {
                            // Capture both successful inserts even if the event INSERT fails.
                            ids.put("acao_pdi", event.getAcaoPdiId());
                            ids.put("acao_pdi_versao", event.getAcaoPdiVersao().getId());
                        }
                        boolean targetFailure = failEvent && (addAction
                                ? "ACAO_ADICIONADA".equals(event.getTipo())
                                : !editDraft || "EDICAO_RASCUNHO".equals(event.getTipo()));
                        if (targetFailure) {
                            if (addAction) {
                                int identities = em.createQuery("select count(a) from AcaoPDI a "
                                                + "where a.id = :id and a.pdi.id = :pdi", Long.class)
                                        .setParameter("id", event.getAcaoPdiId())
                                        .setParameter("pdi", event.getPdiId()).getSingleResult().intValue();
                                int contents = em.createQuery("select count(v) from AcaoPdiVersao v "
                                                + "where v.id = :id and v.pdiId = :pdi "
                                                + "and v.pdiVersaoId = :versao and v.acaoPdiId = :acao", Long.class)
                                        .setParameter("id", event.getAcaoPdiVersao().getId())
                                        .setParameter("pdi", event.getPdiId())
                                        .setParameter("versao", event.getPdiVersaoId())
                                        .setParameter("acao", event.getAcaoPdiId()).getSingleResult().intValue();
                                assertEquals(1, identities, "acao.falha.identidadeInserida");
                                assertEquals(1, contents, "acao.falha.conteudoInserido");
                            }
                            if (editDraft) {
                                // A scalar projection queries the database, not the managed version.
                                // Verify the UPDATE reached PostgreSQL before forcing the INSERT failure.
                                Object[] persisted = em.createQuery(
                                        "select v.titulo, v.objetivo, v.inicioPlanejado, v.fimPlanejado "
                                                + "from PdiVersao v where v.id = :id", Object[].class)
                                        .setParameter("id", event.getPdiVersaoId()).getSingleResult();
                                var edited = editRequest();
                                assertEquals(edited.titulo(), persisted[0], "edicao.flush.titulo");
                                assertEquals(edited.objetivo(), persisted[1], "edicao.flush.objetivo");
                                assertEquals(edited.inicioPlanejado(), persisted[2], "edicao.flush.inicio");
                                assertEquals(edited.fimPlanejado(), persisted[3], "edicao.flush.fim");
                            }
                            // Real PostgreSQL CHECK failure, restricted to this new test entity.
                            // No production behavior or schema is changed.
                            org.springframework.test.util.ReflectionTestUtils.setField(event, "tipo", "");
                        }
                        S saved = realEvents.saveAndFlush(event);
                        eventIds.add(saved.getId());
                        return saved;
                    }
                };
                var identity = new java.util.concurrent.atomic.AtomicReference<UUID>();
                var beans = new org.springframework.beans.factory.support.DefaultListableBeanFactory();
                beans.registerSingleton("testIdentity",
                        (com.empresa.pdi.security.UsuarioAutenticadoProvider) identity::get);
                var mapper = new ObjectMapper().findAndRegisterModules();
                var target = new com.empresa.pdi.service.PdiService(plans, versions,
                        repositories.getRepository(com.empresa.pdi.repository.AcaoPdiVersaoRepository.class),
                        eventWriter, users,
                        beans.getBeanProvider(com.empresa.pdi.security.UsuarioAutenticadoProvider.class), mapper);
                var interceptor = new org.springframework.transaction.interceptor.TransactionInterceptor();
                interceptor.setTransactionManager(manager);
                interceptor.setTransactionAttributeSource(
                        new org.springframework.transaction.annotation.AnnotationTransactionAttributeSource());
                var proxyFactory = new org.springframework.aop.framework.ProxyFactory(target);
                proxyFactory.setProxyTargetClass(true);
                proxyFactory.addAdvice(interceptor);
                var service = (com.empresa.pdi.service.PdiService) proxyFactory.getProxy();

                var outer = manager.getTransaction(new org.springframework.transaction.support.DefaultTransactionDefinition());
                try {
                    String suffix = UUID.randomUUID().toString();
                    Cargo cargo = new Cargo();
                    cargo.setNome("IT service cargo " + suffix);
                    cargo.setNivel(NivelCargo.NAO_APLICAVEL);
                    em.persist(cargo);
                    Equipe equipe = new Equipe();
                    equipe.setNome("IT service equipe " + suffix);
                    em.persist(equipe);
                    Usuario user = new Usuario();
                    user.setNome("IT service user " + suffix);
                    user.setEmail("pdi-service-" + suffix + "@example.invalid");
                    user.setPerfilAcesso(PerfilUsuario.COLABORADOR);
                    user.setCargo(cargo);
                    user.setEquipeAtual(equipe);
                    em.persist(user);
                    em.flush();
                    ids.put("cargo", cargo.getId());
                    ids.put("equipe", equipe.getId());
                    ids.put("usuario", user.getId());
                    identity.set(user.getId());
                    var request = new com.empresa.pdi.dto.CriarPdiRequest("IT service PDI " + suffix,
                            "Persistir plano, versão e evento atomicamente",
                            LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31));
                    if (failEvent && !editDraft && !addAction) {
                        RuntimeException failure = assertThrows(RuntimeException.class, () -> service.criar(request));
                        capturedEventDiagnostic[0] = postgresDiagnostic(failure, eventCalled.get());
                        assertTrue(eventCalled.get(), "Failure must occur at event persistence");
                        assertNotNull(ids.get("pdi"));
                        assertNotNull(ids.get("pdi_versao"));
                        assertTrue(hasSqlState(failure, "23514"), "Expected PostgreSQL CHECK violation");
                        assertTrue(outer.isRollbackOnly(), "Service interceptor must mark transaction rollback-only");
                        System.out.println("Verified event rollback failure: " + capturedEventDiagnostic[0]);
                    } else {
                        var response = service.criar(request);
                        assertTrue(eventCalled.get(), "evento.persistenciaInvocada");
                        em.flush();
                        em.clear();
                        PDI loaded = em.find(PDI.class, response.id());
                        PdiVersao version = em.find(PdiVersao.class, response.versaoId());
                        assertNotNull(loaded, "pdi.persistido");
                        assertNotNull(version, "versao.persistida");
                        assertEquals(user.getId(), loaded.getUsuario().getId(), "pdi.titular");
                        assertEquals(StatusExecucaoPdi.NAO_INICIADO, loaded.getStatusExecucao(), "pdi.statusExecucao");
                        assertNull(loaded.getVersaoVigenteId(), "pdi.versaoVigenteId");
                        assertNull(loaded.getVersaoVigente(), "pdi.versaoVigente");
                        assertEquals(1, version.getNumero(), "versao.numero");
                        assertEquals(StatusAprovacaoPdi.RASCUNHO, version.getStatusAprovacao(), "versao.statusAprovacao");
                        assertEquals(loaded.getId(), version.getPdi().getId(), "versao.pdi");
                        assertEquals(request.titulo(), version.getTitulo(), "versao.titulo");
                        assertEquals(request.objetivo(), version.getObjetivo(), "versao.objetivo");
                        assertEquals(request.inicioPlanejado(), version.getInicioPlanejado(), "versao.inicioPlanejado");
                        assertEquals(request.fimPlanejado(), version.getFimPlanejado(), "versao.fimPlanejado");
                        assertNull(version.getSubmetidaEm(), "versao.submetidaEm");
                        var events = em.createQuery("select e from PdiEvento e where e.pdiId = :id", PdiEvento.class)
                                .setParameter("id", response.id()).getResultList();
                        assertEquals(1, events.size(), "evento.quantidade");
                        PdiEvento event = events.get(0);
                        ids.put("pdi_evento", event.getId());
                        assertEquals("CRIACAO", event.getTipo(), "evento.tipo");
                        assertEquals(user.getId(), event.getAutor().getId(), "evento.autor");
                        assertEquals(response.id(), event.getPdiId(), "evento.pdiId");
                        assertEquals(response.versaoId(), event.getPdiVersaoId(), "evento.pdiVersaoId");
                        assertEquals(response.versaoId(), event.getPdiVersao().getId(), "evento.pdiVersao");
                        assertNull(event.getAcaoPdiId(), "evento.acaoPdiId");
                        assertNull(event.getComponenteId(), "evento.componenteId");
                        assertNull(event.getSubmissao(), "evento.submissao");
                        assertNotNull(event.getOcorridoEm(), "evento.ocorridoEm");
                        assertTrue(jsonExactlyEquivalent(mapper.valueToTree(response), event.getDados()), "evento.dados");
                        assertNull(event.getSnapshot(), "evento.snapshot");
                        if (editDraft) {
                            eventCalled.set(false);
                            verifyEditing(service, em, mapper, response, user.getId(), event,
                                    failEvent, outer, eventCalled, capturedEventDiagnostic);
                        }
                        if (addAction) {
                            eventCalled.set(false);
                            var actionTarget = new com.empresa.pdi.service.AcaoPdiService(
                                    repositories.getRepository(com.empresa.pdi.repository.AcaoPDIRepository.class),
                                    repositories.getRepository(com.empresa.pdi.repository.AcaoPdiVersaoRepository.class),
                                    plans, versions, eventWriter, users,
                                    beans.getBeanProvider(com.empresa.pdi.security.UsuarioAutenticadoProvider.class), mapper);
                            var actionProxyFactory = new org.springframework.aop.framework.ProxyFactory(actionTarget);
                            actionProxyFactory.setProxyTargetClass(true);
                            actionProxyFactory.addAdvice(interceptor);
                            var actionService = (com.empresa.pdi.service.AcaoPdiService) actionProxyFactory.getProxy();
                            verifyActionCreation(actionService, em, mapper, response, user.getId(), event,
                                    failEvent, outer, eventCalled, capturedEventDiagnostic);
                        }
                    }
                } finally {
                    // Never commit: includes fixtures and the real proxied service invocation.
                    manager.rollback(outer);
                }
                connection.setReadOnly(true);
                try {
                    for (var entry : ids.entrySet()) assertAbsent(connection, entry.getKey(), entry.getValue());
                    for (Long eventId : eventIds) assertAbsent(connection, "pdi_evento", eventId);
                    if (addAction) {
                        try (PreparedStatement statement = connection.prepareStatement(
                                "SELECT EXISTS (SELECT 1 FROM public.pdi_evento WHERE pdi_id = ?)")) {
                            statement.setObject(1, ids.get("pdi"));
                            try (ResultSet rows = statement.executeQuery()) {
                                assertTrue(rows.next(), "acao.rollback.consultaEventos");
                                assertFalse(rows.getBoolean(1), "acao.rollback.eventosAusentes");
                            }
                        }
                    }
                } finally {
                    connection.rollback();
                }
            } finally {
                connection.rollback();
            }
        } finally {
            if (registry != null) StandardServiceRegistryBuilder.destroy(registry);
        }
    }

    private static void verifyActionCreation(com.empresa.pdi.service.AcaoPdiService service,
                                              jakarta.persistence.EntityManager em, ObjectMapper mapper,
                                              com.empresa.pdi.dto.PdiRascunhoResponse created, UUID ownerId,
                                              PdiEvento creation, boolean failEvent,
                                              org.springframework.transaction.TransactionStatus outer,
                                              java.util.concurrent.atomic.AtomicBoolean eventCalled,
                                              String[] capturedEventDiagnostic) {
        JsonNode originalCreationData = creation.getDados();
        var request = new com.empresa.pdi.dto.CriarAcaoPdiRequest(
                "IT action", "Practice PostgreSQL", "Verifiable report",
                LocalDate.of(2026, 2, 1), LocalDate.of(2026, 11, 30));
        if (failEvent) {
            RuntimeException failure = assertThrows(RuntimeException.class,
                    () -> service.criar(created.id(), request), "acao.falha.excecao");
            capturedEventDiagnostic[0] = postgresDiagnostic(failure, eventCalled.get());
            assertTrue(eventCalled.get(), "acao.falha.eventoInvocado");
            assertTrue(hasSqlState(failure, "23514"), "acao.falha.sqlstate");
            assertTrue(capturedEventDiagnostic[0].contains("classification=EXPECTED_EVENT_CHECK"),
                    "acao.falha.constraint");
            assertTrue(outer.isRollbackOnly(), "acao.falha.rollbackOnly");
            // No queries after the PostgreSQL error until the outer finally rolls back.
            return;
        }
        var response = service.criar(created.id(), request);
        assertTrue(eventCalled.get(), "acao.eventoInvocado");
        em.flush();
        em.clear();
        AcaoPDI action = em.find(AcaoPDI.class, response.id());
        AcaoPdiVersao content = em.find(AcaoPdiVersao.class, response.acaoVersaoId());
        assertNotNull(action, "acao.identidade.persistida");
        assertNotNull(content, "acao.conteudo.persistido");
        assertEquals(response.id(), action.getId(), "acao.identidade.id");
        assertEquals(created.id(), action.getPdi().getId(), "acao.identidade.pdi");
        assertEquals(ownerId, action.getPdi().getUsuario().getId(), "acao.identidade.titular");
        assertEquals(StatusExecucaoAcao.NAO_INICIADA, action.getStatusExecucao(), "acao.identidade.estado");
        assertNull(action.getIniciadaEm(), "acao.identidade.inicio");
        assertNull(action.getConcluidaEm(), "acao.identidade.conclusao");
        assertNull(action.getConcluidaPor(), "acao.identidade.concluidaPor");
        assertEquals(response.acaoVersaoId(), content.getId(), "acao.conteudo.id");
        assertEquals(created.id(), content.getPdiId(), "acao.conteudo.pdiId");
        assertEquals(created.versaoId(), content.getPdiVersaoId(), "acao.conteudo.pdiVersaoId");
        assertEquals(response.id(), content.getAcaoPdiId(), "acao.conteudo.acaoId");
        assertEquals(created.versaoId(), content.getPdiVersao().getId(), "acao.conteudo.versao");
        assertEquals(response.id(), content.getAcaoPdi().getId(), "acao.conteudo.acao");
        assertEquals(request.titulo(), content.getTitulo(), "acao.conteudo.titulo");
        assertEquals(request.objetivo(), content.getObjetivo(), "acao.conteudo.objetivo");
        assertEquals(request.resultadoEsperado(), content.getResultadoEsperado(), "acao.conteudo.resultado");
        assertEquals(request.inicioPlanejado(), content.getInicioPlanejado(), "acao.conteudo.inicio");
        assertEquals(request.fimPlanejado(), content.getFimPlanejado(), "acao.conteudo.fim");
        assertEquals(0, content.getOrdem(), "acao.conteudo.ordem");
        assertEquals(created.id(), response.pdiId(), "acao.response.pdiId");
        assertEquals(created.versaoId(), response.pdiVersaoId(), "acao.response.versaoId");
        PDI plan = em.find(PDI.class, created.id());
        PdiVersao version = em.find(PdiVersao.class, created.versaoId());
        assertEquals(ownerId, plan.getUsuario().getId(), "acao.pdi.titularPreservado");
        assertEquals(StatusExecucaoPdi.NAO_INICIADO, plan.getStatusExecucao(), "acao.pdi.estadoPreservado");
        assertNull(plan.getVersaoVigenteId(), "acao.pdi.semVersaoVigente");
        assertEquals(1, version.getNumero(), "acao.pdi.numeroPreservado");
        assertEquals(StatusAprovacaoPdi.RASCUNHO, version.getStatusAprovacao(), "acao.pdi.rascunhoPreservado");
        assertNull(version.getSubmetidaEm(), "acao.pdi.semSubmissao");
        assertEquals(created.titulo(), version.getTitulo(), "acao.pdi.tituloPreservado");
        assertEquals(created.objetivo(), version.getObjetivo(), "acao.pdi.objetivoPreservado");
        assertEquals(created.inicioPlanejado(), version.getInicioPlanejado(), "acao.pdi.inicioPreservado");
        assertEquals(created.fimPlanejado(), version.getFimPlanejado(), "acao.pdi.fimPreservado");
        var history = em.createQuery("select e from PdiEvento e where e.pdiId = :id", PdiEvento.class)
                .setParameter("id", created.id()).getResultList();
        assertEquals(2, history.size(), "acao.historico.quantidade");
        var additions = history.stream().filter(e -> "ACAO_ADICIONADA".equals(e.getTipo())).toList();
        assertEquals(1, additions.size(), "acao.evento.quantidade");
        PdiEvento event = additions.get(0);
        assertEquals(ownerId, event.getAutor().getId(), "acao.evento.autor");
        assertEquals(created.id(), event.getPdiId(), "acao.evento.pdiId");
        assertEquals(created.versaoId(), event.getPdiVersaoId(), "acao.evento.pdiVersaoId");
        assertEquals(created.versaoId(), event.getPdiVersao().getId(), "acao.evento.versao");
        assertEquals(response.id(), event.getAcaoPdiId(), "acao.evento.acaoId");
        assertEquals(response.id(), event.getAcaoPdi().getId(), "acao.evento.acao");
        assertEquals(response.acaoVersaoId(), event.getAcaoPdiVersao().getId(), "acao.evento.conteudo");
        assertNotNull(event.getOcorridoEm(), "acao.evento.data");
        assertNull(event.getComponenteId(), "acao.evento.semComponente");
        assertNull(event.getSubmissao(), "acao.evento.semSubmissao");
        assertNull(event.getSnapshot(), "acao.evento.semSnapshot");
        assertTrue(jsonExactlyEquivalent(mapper.valueToTree(response), event.getDados()), "acao.evento.dados");
        PdiEvento original = em.find(PdiEvento.class, creation.getId());
        assertEquals("CRIACAO", original.getTipo(), "acao.criacao.tipoPreservado");
        assertEquals(ownerId, original.getAutor().getId(), "acao.criacao.autorPreservado");
        assertNull(original.getAcaoPdiId(), "acao.criacao.semAcao");
        assertNull(original.getSnapshot(), "acao.criacao.semSnapshot");
        assertTrue(jsonExactlyEquivalent(originalCreationData, original.getDados()), "acao.criacao.dadosPreservados");
    }

    private static com.empresa.pdi.dto.AtualizarPdiRascunhoRequest editRequest() {
        return new com.empresa.pdi.dto.AtualizarPdiRascunhoRequest(
                "IT edited draft", "Updated development objective",
                LocalDate.of(2026, 2, 1), LocalDate.of(2026, 11, 30));
    }

    private static void verifyEditing(com.empresa.pdi.service.PdiService service,
                                      jakarta.persistence.EntityManager em, ObjectMapper mapper,
                                      com.empresa.pdi.dto.PdiRascunhoResponse created, UUID ownerId,
                                      PdiEvento creation, boolean failEvent,
                                      org.springframework.transaction.TransactionStatus outer,
                                      java.util.concurrent.atomic.AtomicBoolean eventCalled,
                                      String[] capturedEventDiagnostic) {
        // Read the baseline after clear so SQL timestamp precision matches the snapshot.
        var before = service.buscarRascunho(created.id());
        JsonNode beforeJson = mapper.valueToTree(before);
        JsonNode originalCreationData = creation.getDados();
        var request = editRequest();
        if (failEvent) {
            RuntimeException failure = assertThrows(RuntimeException.class,
                    () -> service.atualizarRascunho(created.id(), request), "edicao.falha.excecao");
            capturedEventDiagnostic[0] = postgresDiagnostic(failure, eventCalled.get());
            assertTrue(eventCalled.get(), "edicao.falha.eventoInvocado");
            assertTrue(hasSqlState(failure, "23514"), "edicao.falha.sqlstate");
            assertTrue(capturedEventDiagnostic[0].contains("classification=EXPECTED_EVENT_CHECK"),
                    "edicao.falha.constraint");
            assertTrue(outer.isRollbackOnly(), "edicao.falha.rollbackOnly");
            // PostgreSQL has aborted the transaction. Never query through this EntityManager
            // again: the outer finally rolls back fixtures, the UPDATE and all events.
            return;
        }
        var updated = service.atualizarRascunho(created.id(), request);
        assertTrue(eventCalled.get(), "edicao.eventoInvocado");
        em.flush();
        em.clear();
        PDI plan = em.find(PDI.class, created.id());
        PdiVersao version = em.find(PdiVersao.class, created.versaoId());
        assertNotNull(plan, "edicao.pdi.persistido");
        assertNotNull(version, "edicao.versao.persistida");
        assertEquals(created.id(), plan.getId(), "edicao.pdi.id");
        assertEquals(ownerId, plan.getUsuario().getId(), "edicao.pdi.titular");
        assertEquals(StatusExecucaoPdi.NAO_INICIADO, plan.getStatusExecucao(), "edicao.pdi.status");
        assertNull(plan.getVersaoVigenteId(), "edicao.pdi.versaoVigenteId");
        assertNull(plan.getVersaoVigente(), "edicao.pdi.versaoVigente");
        assertEquals(created.versaoId(), version.getId(), "edicao.versao.id");
        assertEquals(created.id(), version.getPdi().getId(), "edicao.versao.pdi");
        assertEquals(1, version.getNumero(), "edicao.versao.numero");
        assertEquals(StatusAprovacaoPdi.RASCUNHO, version.getStatusAprovacao(), "edicao.versao.status");
        assertNull(version.getSubmetidaEm(), "edicao.versao.submetidaEm");
        assertEquals(request.titulo(), version.getTitulo(), "edicao.versao.titulo");
        assertEquals(request.objetivo(), version.getObjetivo(), "edicao.versao.objetivo");
        assertEquals(request.inicioPlanejado(), version.getInicioPlanejado(), "edicao.versao.inicio");
        assertEquals(request.fimPlanejado(), version.getFimPlanejado(), "edicao.versao.fim");
        assertEquals(created.id(), updated.id(), "edicao.response.id");
        assertEquals(created.versaoId(), updated.versaoId(), "edicao.response.versaoId");
        assertTrue(jsonExactlyEquivalent(mapper.valueToTree(updated),
                mapper.valueToTree(service.buscarRascunho(created.id()))), "edicao.response.conteudo");
        var history = em.createQuery("select e from PdiEvento e where e.pdiId = :id", PdiEvento.class)
                .setParameter("id", created.id()).getResultList();
        assertEquals(2, history.size(), "edicao.historico.quantidade");
        var edits = history.stream().filter(e -> "EDICAO_RASCUNHO".equals(e.getTipo())).toList();
        assertEquals(1, edits.size(), "edicao.evento.quantidade");
        PdiEvento event = edits.get(0);
        assertEquals(ownerId, event.getAutor().getId(), "edicao.evento.autor");
        assertEquals(created.id(), event.getPdiId(), "edicao.evento.pdiId");
        assertEquals(created.versaoId(), event.getPdiVersaoId(), "edicao.evento.versaoId");
        assertEquals(created.versaoId(), event.getPdiVersao().getId(), "edicao.evento.versao");
        assertNotNull(event.getOcorridoEm(), "edicao.evento.ocorridoEm");
        assertNull(event.getAcaoPdiId(), "edicao.evento.acao");
        assertNull(event.getComponenteId(), "edicao.evento.componente");
        assertNull(event.getSubmissao(), "edicao.evento.submissao");
        assertTrue(jsonExactlyEquivalent(beforeJson, event.getSnapshot()), "edicao.evento.snapshot");
        assertTrue(jsonExactlyEquivalent(mapper.valueToTree(updated), event.getDados()), "edicao.evento.dados");
        PdiEvento original = em.find(PdiEvento.class, creation.getId());
        assertEquals("CRIACAO", original.getTipo(), "edicao.criacao.tipo");
        assertTrue(jsonExactlyEquivalent(originalCreationData, original.getDados()), "edicao.criacao.dados");
        assertNull(original.getSnapshot(), "edicao.criacao.snapshot");
    }

    /** Compare all JSON content; only numeric representation differences are ignored. */
    private static boolean jsonExactlyEquivalent(JsonNode expected, JsonNode actual) {
        if (expected == null || actual == null) return expected == actual;
        if (expected.isNumber() && actual.isNumber()) {
            // Never normalize binary floating-point nodes: precision may already be lost.
            if (expected.isDouble() || expected.isFloat() || actual.isDouble() || actual.isFloat()) {
                return false;
            }
            return expected.decimalValue().compareTo(actual.decimalValue()) == 0;
        }
        if (expected.getNodeType() != actual.getNodeType()) return false;
        if (expected.isObject()) {
            if (expected.size() != actual.size()) return false;
            var fields = expected.fields();
            while (fields.hasNext()) {
                var field = fields.next();
                if (!actual.has(field.getKey())
                        || !jsonExactlyEquivalent(field.getValue(), actual.get(field.getKey()))) return false;
            }
            return true;
        }
        if (expected.isArray()) {
            if (expected.size() != actual.size()) return false;
            for (int i = 0; i < expected.size(); i++) {
                if (!jsonExactlyEquivalent(expected.get(i), actual.get(i))) return false;
            }
            return true;
        }
        return expected.equals(actual);
    }

    private static boolean hasSqlState(Throwable failure, String expected) {
        Set<Throwable> seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        for (Throwable current = failure; current != null && seen.add(current); current = current.getCause()) {
            if (current instanceof SQLException sql && expected.equals(sql.getSQLState())) return true;
        }
        return false;
    }

    private static String postgresDiagnostic(Throwable failure, boolean eventFailureCandidate) {
        Set<Throwable> seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        String operation = "UNAVAILABLE";
        PSQLException postgres = null;
        for (Throwable current = failure; current != null && seen.add(current); current = current.getCause()) {
            if (current instanceof org.hibernate.JDBCException jdbcFailure && jdbcFailure.getSQL() != null) {
                // Inspect only the leading verb; never return SQL or bind parameters.
                String sql = jdbcFailure.getSQL().stripLeading();
                int end = 0;
                while (end < sql.length() && Character.isLetter(sql.charAt(end))) end++;
                String verb = sql.substring(0, end).toUpperCase(java.util.Locale.ROOT);
                if (Set.of("SELECT", "INSERT", "UPDATE", "DELETE").contains(verb)) operation = verb;
            }
            if (current instanceof PSQLException sqlFailure) {
                postgres = sqlFailure;
                break;
            }
        }
        if (postgres == null) return "postgres=UNAVAILABLE; operation=" + operation;
        String state = postgres.getSQLState();
        if (state == null || !state.matches("[0-9A-Z]{5}")) state = "UNAVAILABLE";
        var server = postgres.getServerErrorMessage();
        String schema = server == null ? null : server.getSchema();
        String table = server == null ? null : server.getTable();
        String column = server == null ? null : server.getColumn();
        String constraint = server == null ? null : server.getConstraint();
        boolean expected = eventFailureCandidate && "23514".equals(state)
                && "ck_evento_tipo".equals(constraint);
        return "postgres=PRESENT; sqlstate=" + state + "; operation=" + operation
                + "; classification=" + (expected ? "EXPECTED_EVENT_CHECK" : "UNEXPECTED")
                + "; schema=" + safeIdentifier(schema, Set.of("public"))
                + "; table=" + safeIdentifier(table, Set.of("cargo", "equipe", "usuario", "historico_equipe",
                        "pdi", "pdi_versao", "acao_pdi", "acao_pdi_versao", "pdi_componente",
                        "pdi_componente_versao", "entrega_submissao", "pdi_evento", "flyway_schema_history"))
                + "; column=" + safeIdentifier(column, Set.of("id", "nome", "descricao", "nivel", "ativo",
                        "lider_id", "ad_object_id", "email", "cargo_id", "gestor_id", "perfil_acesso",
                        "data_criacao", "data_atualizacao", "equipe_atual_id", "usuario_id", "versao_vigente_id",
                        "status_execucao", "criado_em", "iniciado_em", "concluido_em", "concluido_por_id",
                        "lock_version", "pdi_id", "numero", "titulo", "objetivo", "inicio_planejado",
                        "fim_planejado", "status_aprovacao", "criada_em", "submetida_em", "gestor_na_submissao_id",
                        "decidida_em", "decidido_por_id", "justificativa_decisao", "retirada_em", "pdi_versao_id",
                        "acao_pdi_id", "componente_id", "submissao_id", "autor_id", "tipo", "ocorrido_em",
                        "justificativa", "dados", "snapshot"))
                + "; constraint=" + safeIdentifier(constraint, Set.of("pk_cargo", "pk_equipe", "pk_usuario",
                        "ck_cargo_nivel", "uq_equipe_lider", "uq_usuario_ad_object_id", "uq_usuario_email_lower",
                        "ck_usuario_perfil_acesso", "ck_usuario_gestor_nao_proprio", "fk_usuario_cargo",
                        "fk_usuario_gestor", "fk_usuario_equipe_atual", "fk_equipe_lider", "pdi_pkey", "pdi_versao_pkey",
                        "pdi_evento_pkey", "pdi_usuario_id_fkey", "pdi_versao_pdi_id_fkey", "pdi_evento_autor_id_fkey",
                        "ck_pdi_execucao", "ck_pdi_lock_version", "ck_pdi_execucao_datas", "ck_pdi_conclusao_data",
                        "ck_pdi_execucao_versao", "fk_pdi_versao_vigente", "uq_pdi_versao_numero",
                        "uq_pdi_versao_pertencimento", "uq_pdi_proposta_aberta", "ck_pdi_versao_datas",
                        "ck_pdi_versao_status", "ck_pdi_versao_fluxo", "ck_pdi_versao_reprovacao",
                        "ck_pdi_versao_submissao_data", "ck_pdi_versao_decisao_data", "ck_pdi_versao_retirada_data",
                        "fk_evento_versao", "fk_evento_acao", "fk_evento_acao_versao", "fk_evento_componente",
                        "ck_evento_componente_acao", "ck_evento_submissao_componente", "ck_evento_tipo",
                        "ck_evento_dados", "ck_evento_snapshot", "acao_pdi_pkey", "acao_pdi_versao_pkey",
                        "acao_pdi_pdi_id_fkey", "acao_pdi_concluida_por_id_fkey", "uq_acao_pdi_pertencimento",
                        "fk_acao_versao_plano", "fk_acao_versao_identidade", "uq_acao_versao",
                        "uq_acao_versao_pertencimento", "ck_acao_pdi_execucao", "ck_acao_pdi_execucao_datas",
                        "ck_acao_pdi_conclusao_data", "ck_acao_versao_datas"));
    }

    private static String safeIdentifier(String value, Set<String> allowed) {
        if (value == null) return "UNAVAILABLE";
        return allowed.contains(value) ? value : "REDACTED";
    }

    private static final class SingleConnectionProvider implements ConnectionProvider {
        private final Connection guarded;
        SingleConnectionProvider(Connection connection) {
            guarded = (Connection) java.lang.reflect.Proxy.newProxyInstance(
                    PdiServicePersistenceIT.class.getClassLoader(), new Class<?>[]{Connection.class},
                    (proxy, method, arguments) -> {
                        if (method.getName().equals("commit")
                                || (method.getName().equals("setAutoCommit") && Boolean.TRUE.equals(arguments[0]))) {
                            throw new SQLException("Commit is forbidden in this test");
                        }
                        if (method.getName().equals("close")) return null;
                        if (method.getName().equals("unwrap")) {
                            Class<?> type = (Class<?>) arguments[0];
                            if (type.isInstance(proxy)) return proxy;
                            throw new SQLException("Raw connection unwrap is forbidden");
                        }
                        if (method.getName().equals("isWrapperFor")) return ((Class<?>) arguments[0]).isInstance(proxy);
                        try {
                            return method.invoke(connection, arguments);
                        } catch (java.lang.reflect.InvocationTargetException ex) {
                            throw ex.getCause();
                        }
                    });
        }
        @Override public Connection getConnection() { return guarded; }
        @Override public void closeConnection(Connection connection) { /* Physical owner closes it. */ }
        @Override public boolean supportsAggressiveRelease() { return false; }
        @Override public boolean isUnwrappableAs(Class<?> type) { return type.isInstance(this); }
        @Override public <T> T unwrap(Class<T> type) {
            if (type.isInstance(this)) return type.cast(this);
            throw new IllegalArgumentException("Unsupported unwrap");
        }
    }

    private static void assertAbsent(Connection connection, String table, Object id) throws SQLException {
        if (id == null) return;
        // Table names are fixed internal literals, never configuration or request input.
        try (PreparedStatement statement = connection.prepareStatement("SELECT EXISTS (SELECT 1 FROM public." + table + " WHERE id = ?)")) {
            statement.setObject(1, id);
            try (ResultSet rows = statement.executeQuery()) {
                assertTrue(rows.next(), "rollback." + table + ".resultado");
                assertFalse(rows.getBoolean(1), "rollback." + table + ".ausencia");
            }
        }
    }

    private static Config configuration() {
        String host = required("HOST");
        String database = required("DATABASE");
        String user = required("USER");
        if (!"pdi_db".equals(database)) throw new IllegalStateException("Unexpected database");
        if (!"talentsynk_test_runner".equals(user)) throw new IllegalStateException("Unexpected JDBC principal");
        if (!host.matches("[a-z0-9.-]+\\.neon\\.tech") || host.contains("-pooler")
                || !database.matches("[A-Za-z0-9_-]+")) throw new IllegalStateException("Invalid destination");
        if (!"public".equals(required("SCHEMA"))) throw new IllegalStateException("Schema must be public");
        return new Config(host, database, user, required("PASSWORD"),
                identifier("PROJECT_ID"), identifier("BRANCH_ID"), identifier("ENDPOINT_ID"), required("NEON_API_KEY"));
    }

    private static String identifier(String name) {
        String value = required(name);
        if (!value.matches("[a-z0-9-]{1,100}")) throw new IllegalStateException("Invalid resource identifier");
        return value;
    }

    private static String required(String name) {
        String value = System.getenv(PREFIX + name);
        if (value == null || value.isBlank()) throw new IllegalStateException("Missing environment setting: " + PREFIX + name);
        return value;
    }

    private static void verifyNeonIdentity(Config config) throws Exception {
        try (HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NEVER).build()) {
            JsonNode branch = api(client, config, "/branches/" + config.branchId()).path("branch");
            if (!config.branchId().equals(branch.path("id").asText())
                    || !config.projectId().equals(branch.path("project_id").asText())
                    || !APPROVED_BRANCH.equals(branch.path("name").asText())
                    || !branch.path("default").isBoolean() || branch.path("default").asBoolean()) {
                throw new IllegalStateException("Neon branch identity not proven or branch is default");
            }
            JsonNode endpoint = api(client, config, "/endpoints/" + config.endpointId()).path("endpoint");
            if (!config.endpointId().equals(endpoint.path("id").asText())
                    || !config.branchId().equals(endpoint.path("branch_id").asText())
                    || !config.projectId().equals(endpoint.path("project_id").asText())
                    || !config.host().equals(endpoint.path("host").asText())
                    || !"read_write".equals(endpoint.path("type").asText())) {
                throw new IllegalStateException("Neon endpoint identity not proven");
            }
        }
    }

    private static JsonNode api(HttpClient client, Config config, String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("https://console.neon.tech/api/v2/projects/" + config.projectId() + path))
                .timeout(Duration.ofSeconds(20)).header("Authorization", "Bearer " + config.apiKey())
                .header("Accept", "application/json").GET().build();
        var response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) throw new IllegalStateException("Neon identity API unavailable");
        return new ObjectMapper().readTree(response.body());
    }

    private static void verifyDatabase(Connection connection, Config config) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(
                "SELECT current_database(), current_schema(), current_user, session_user, current_setting('transaction_read_only')")) {
            assertTrue(rows.next());
            assertTrue(config.database().equals(rows.getString(1)), "Unexpected database");
            assertTrue("public".equals(rows.getString(2)), "Unexpected schema");
            assertTrue(config.user().equals(rows.getString(3)) && config.user().equals(rows.getString(4)), "Unexpected principal");
            assertTrue("on".equals(rows.getString(5)), "Preflight must be read-only");
        }
    }

    private static void verifyMigrations(Connection connection) throws Exception {
        // Run only after V3 has been explicitly authorized, applied and confirmed on Neon.
        Map<String, String> scripts = Map.of(
                "1", "V1__create_core_tables.sql",
                "2", "V2__create_pdi_initial_flow.sql",
                "3", "V3__create_usuario_credencial_local.sql");
        Set<String> found = new java.util.HashSet<>();
        try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(
                "SELECT version, script, checksum, success FROM public.flyway_schema_history ORDER BY installed_rank")) {
            while (rows.next()) {
                String version = rows.getString(1);
                assertTrue(scripts.containsKey(version), "Unexpected Flyway entry");
                assertTrue(found.add(version), "Duplicate migration");
                assertTrue(scripts.get(version).equals(rows.getString(2)), "Unexpected migration script");
                int checksum = rows.getInt(3);
                assertFalse(rows.wasNull(), "Missing migration checksum");
                assertEquals(localChecksum(scripts.get(version)), checksum, "Migration checksum mismatch");
                if ("1".equals(version)) assertEquals(467754627, checksum);
                assertTrue(rows.getBoolean(4), "Migration not successful");
            }
        }
        assertEquals(scripts.keySet(), found);
    }

    private static int localChecksum(String script) throws Exception {
        CRC32 crc = new CRC32();
        try (var stream = PdiServicePersistenceIT.class.getResourceAsStream("/db/migration/" + script)) {
            if (stream == null) throw new IllegalStateException("Migration resource missing");
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                String line;
                boolean first = true;
                while ((line = reader.readLine()) != null) {
                    if (first && line.startsWith("\uFEFF")) line = line.substring(1);
                    first = false;
                    crc.update(line.getBytes(StandardCharsets.UTF_8));
                }
            }
        }
        return (int) crc.getValue();
    }

    // Action editing has its own runner: the six original scenarios above remain unchanged.
    private static final String ACTION_EDIT_COMMIT_FLAG = "TALENTSYNK_PDI_ACTION_EDIT_BASELINE_COMMIT_ENABLED";
    private static final Set<String> ACTION_EDIT_FIELDS = Set.of(
            "titulo", "objetivo", "inicioPlanejado", "fimPlanejado", "resultadoEsperado");
    private enum ActionEditMode { EDIT, NULLS, UNCHANGED, INVALID_DATES, ROLLBACK }

    private void executeActionEditScenario(ActionEditMode mode) {
        org.junit.jupiter.api.Assumptions.assumeTrue(
                "true".equals(System.getenv("TALENTSYNK_PDI_SERVICE_PERSISTENCE_ENABLED")));
        if (mode == ActionEditMode.ROLLBACK) {
            org.junit.jupiter.api.Assumptions.assumeTrue("true".equals(System.getenv(ACTION_EDIT_COMMIT_FLAG)));
        }
        Logger hibernate = (Logger) LoggerFactory.getLogger("org.hibernate");
        Level previousLevel = hibernate.getLevel();
        java.util.logging.Logger jdbc = java.util.logging.Logger.getLogger("org.postgresql");
        java.util.logging.Level previousJdbc = jdbc.getLevel();
        String stage = "action edit preflight";
        try {
            hibernate.setLevel(Level.OFF);
            jdbc.setLevel(java.util.logging.Level.OFF);
            Config config = configuration();
            stage = "action edit " + mode.name();
            if (mode == ActionEditMode.ROLLBACK) runCommittedActionEditRollback(config);
            else runRolledBackActionEdit(config, mode);
        } catch (Exception | AssertionError failure) {
            String label = "UNAVAILABLE";
            Set<Throwable> seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
            for (Throwable cause = failure; cause != null && seen.add(cause); cause = cause.getCause()) {
                String message = cause.getMessage();
                if (message != null) {
                    String candidate = message.split(" ==> ", 2)[0];
                    if (candidate.matches("actionEdit\\.[A-Za-z0-9_.]+")) { label = candidate; break; }
                }
            }
            throw new AssertionError("PDI action edit failed at: " + stage + "; assertion=" + label
                    + "; " + postgresDiagnostic(failure, false), sanitizedCauseChain(failure));
        } finally {
            hibernate.setLevel(previousLevel);
            jdbc.setLevel(previousJdbc);
        }
    }

    private static void actionEditCheck(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
    }

    private static Connection openActionEditConnection(Config config) throws Exception {
        verifyNeonIdentity(config); // Independent branch/endpoint check BEFORE every JDBC connection.
        Properties properties = new Properties();
        properties.setProperty("user", config.user());
        properties.setProperty("password", config.password());
        properties.setProperty("sslmode", "verify-full");
        properties.setProperty("sslfactory", "org.postgresql.ssl.DefaultJavaSSLFactory");
        properties.setProperty("connectTimeout", "15");
        properties.setProperty("socketTimeout", "30");
        properties.setProperty("readOnlyMode", "transaction");
        properties.setProperty("options", "-c search_path=public -c statement_timeout=15000 -c lock_timeout=5000");
        Connection connection = DriverManager.getConnection(config.url(), properties);
        try {
            connection.setReadOnly(true);
            connection.setAutoCommit(false);
            var actual = URI.create(connection.getMetaData().getURL().substring("jdbc:".length()));
            actionEditCheck(config.host().equals(actual.getHost()) && actual.getPort() == 5432
                    && ("/" + config.database()).equals(actual.getPath()) && actual.getUserInfo() == null,
                    "actionEdit.destination.metadata");
            verifyDatabase(connection, config);
            verifyMigrations(connection);
            verifyActionEditPrivileges(connection);
            connection.rollback();
            return connection;
        } catch (Exception | AssertionError failure) {
            try { connection.rollback(); } finally { connection.close(); }
            throw failure;
        }
    }

    private static void verifyActionEditPrivileges(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT has_schema_privilege(current_user, 'public', 'USAGE')")) {
            actionEditCheck(rows.next() && rows.getBoolean(1), "actionEdit.privilege.schema");
        }
        for (String table : java.util.List.of("cargo", "equipe", "usuario", "pdi", "pdi_versao",
                "acao_pdi", "acao_pdi_versao", "pdi_evento", "flyway_schema_history")) {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT has_table_privilege(current_user, ?, 'SELECT'), has_table_privilege(current_user, ?, 'INSERT')")) {
                statement.setString(1, "public." + table);
                statement.setString(2, "public." + table);
                try (ResultSet rows = statement.executeQuery()) {
                    actionEditCheck(rows.next() && rows.getBoolean(1), "actionEdit.privilege.select." + table);
                    if (!"flyway_schema_history".equals(table)) {
                        actionEditCheck(rows.getBoolean(2), "actionEdit.privilege.insert." + table);
                    }
                }
            }
        }
        for (String table : java.util.List.of("usuario", "pdi", "pdi_versao")) {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT has_any_column_privilege(current_user, ?, 'UPDATE')")) {
                statement.setString(1, "public." + table);
                try (ResultSet rows = statement.executeQuery()) {
                    actionEditCheck(rows.next() && rows.getBoolean(1), "actionEdit.privilege.lock." + table);
                }
            }
        }
        // No @DynamicUpdate: Hibernate may include preserved shared FKs and ordem in its UPDATE.
        for (String column : java.util.List.of("pdi_id", "pdi_versao_id", "acao_pdi_id", "titulo",
                "objetivo", "resultado_esperado", "inicio_planejado", "fim_planejado", "ordem")) {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT has_column_privilege(current_user, 'public.acao_pdi_versao', ?, 'UPDATE')")) {
                statement.setString(1, column);
                try (ResultSet rows = statement.executeQuery()) {
                    actionEditCheck(rows.next() && rows.getBoolean(1), "actionEdit.privilege.update." + column);
                }
            }
        }
        // IDENTITY generation is internal; do not require unrelated sequence USAGE grants.
        for (String table : java.util.List.of("pdi", "pdi_versao", "acao_pdi", "acao_pdi_versao", "pdi_evento")) {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT is_identity, identity_generation FROM information_schema.columns "
                            + "WHERE table_schema = 'public' AND table_name = ? AND column_name = 'id'")) {
                statement.setString(1, table);
                try (ResultSet rows = statement.executeQuery()) {
                    actionEditCheck(rows.next() && "YES".equals(rows.getString(1))
                            && "BY DEFAULT".equals(rows.getString(2)), "actionEdit.identity." + table);
                }
            }
        }
    }

    private static void runRolledBackActionEdit(Config config, ActionEditMode mode) throws Exception {
        try (Connection connection = openActionEditConnection(config);
             ActionEditHarness harness = new ActionEditHarness(connection, false)) {
            verifyNeonIdentity(config); // Recheck immediately before the first write.
            connection.setReadOnly(false);
            var outer = harness.manager.getTransaction(new org.springframework.transaction.support.DefaultTransactionDefinition());
            try {
                ActionEditFixture fixture = createActionEditFixture(harness, mode == ActionEditMode.EDIT);
                harness.em.flush();
                harness.em.clear();
                ActionEditState before = readActionEditState(connection, fixture);
                verifyActionEditBaseline(fixture, before);
                var request = switch (mode) {
                    case NULLS -> new com.empresa.pdi.dto.AtualizarAcaoPdiRequest(null, null, null, null, null);
                    case UNCHANGED -> actionEditRequestFromRow(before.content());
                    case INVALID_DATES -> new com.empresa.pdi.dto.AtualizarAcaoPdiRequest(
                            "IT invalid dates", null, LocalDate.of(2026, 11, 30), LocalDate.of(2026, 2, 1), null);
                    default -> actionEditRequest();
                };
                harness.sql.start(); // Excludes all fixture writes and baseline queries.
                if (mode == ActionEditMode.INVALID_DATES) {
                    assertThrows(IllegalArgumentException.class,
                            () -> harness.actions.atualizarRascunho(fixture.pdiId(), fixture.actionId(), request),
                            "actionEdit.invalidDates.rejected");
                    actionEditCheck(outer.isRollbackOnly(), "actionEdit.invalidDates.rollbackOnly");
                } else {
                    var response = harness.actions.atualizarRascunho(fixture.pdiId(), fixture.actionId(), request);
                    verifyActionEditResponse(response, fixture, request, before);
                }
                harness.em.flush();
                harness.em.clear();
                if (mode == ActionEditMode.UNCHANGED || mode == ActionEditMode.INVALID_DATES) {
                    actionEditCheck(harness.sql.writes == 0, "actionEdit.noChange.noDml");
                }
                ActionEditState after = readActionEditState(connection, fixture);
                verifyActionEditProtectedState(before, after);
                if (mode == ActionEditMode.UNCHANGED || mode == ActionEditMode.INVALID_DATES) {
                    verifyActionEditSameState(before, after);
                } else {
                    actionEditCheck(jsonExactlyEquivalent(actionEditJson(request), actionEditContent(after.content())),
                            "actionEdit.content.persisted");
                    verifyActionEditHistory(harness, fixture, before, after, request);
                }
            } finally {
                try { harness.manager.rollback(outer); }
                finally {
                    connection.setReadOnly(true);
                    try { verifyActionEditFixturesAbsent(connection, harness.ledger); }
                    finally { connection.rollback(); }
                }
            }
        }
    }

    private static void runCommittedActionEditRollback(Config config) throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue("true".equals(System.getenv(ACTION_EDIT_COMMIT_FLAG)));
        ActionEditFixture fixture;
        // T0 has a dedicated one-shot gate. The original provider and all other tests stay commit-free.
        try (Connection connection = openActionEditConnection(config);
             ActionEditHarness setup = new ActionEditHarness(connection, true)) {
            verifyNeonIdentity(config);
            connection.setReadOnly(false);
            var initial = setup.manager.getTransaction(new org.springframework.transaction.support.DefaultTransactionDefinition());
            try {
                fixture = createActionEditFixture(setup, false);
                setup.em.flush();
                setup.em.clear();
                verifyActionEditBaseline(fixture, readActionEditState(connection, fixture));
                verifyNeonIdentity(config);
                setup.provider.armFixtureCommit();
                setup.manager.commit(initial);
                actionEditCheck(setup.provider.commitConfirmed, "actionEdit.T0.commitConfirmed");
            } finally {
                try {
                    if (!initial.isCompleted()) setup.manager.rollback(initial);
                    connection.rollback();
                } finally {
                    if (setup.provider.commitAttempted) {
                        // Only synthetic run markers and database IDs: never names, payloads or credentials.
                        System.out.println("Action-edit T0 fixtures: state="
                                + (setup.provider.commitConfirmed ? "COMMITTED" : "COMMIT_UNCERTAIN_DO_NOT_RETRY")
                                + "; run=" + setup.ledger.marker + "; rows=" + setup.ledger.rows);
                    }
                }
            }
        }
        // An independent read after T0 captures database precision and proves committed visibility.
        ActionEditState baseline = readCommittedActionEditState(config, fixture);
        verifyActionEditBaseline(fixture, baseline);
        // T1: no outer transaction. The production @Transactional proxy owns the physical rollback.
        try (Connection connection = openActionEditConnection(config);
             ActionEditHarness attempt = new ActionEditHarness(connection, false)) {
            verifyNeonIdentity(config);
            connection.setReadOnly(false);
            try {
                attempt.identity.set(fixture.userId());
                attempt.failureTarget = fixture;
                actionEditCheck(!org.springframework.transaction.support.TransactionSynchronizationManager
                        .isActualTransactionActive(), "actionEdit.T1.noOuterTransaction");
                int rollbacksBefore = attempt.provider.rollbacks;
                RuntimeException failure = assertThrows(RuntimeException.class,
                        () -> attempt.actions.atualizarRascunho(fixture.pdiId(), fixture.actionId(), actionEditRequest()),
                        "actionEdit.T1.failure");
                actionEditCheck(attempt.failureInjected && attempt.updateObserved, "actionEdit.T1.updateReachedDatabase");
                actionEditCheck(hasSqlState(failure, "23514")
                        && postgresDiagnostic(failure, true).contains("constraint=ck_evento_tipo"),
                        "actionEdit.T1.expectedCheck");
                actionEditCheck(attempt.provider.rollbacks == rollbacksBefore + 1,
                        "actionEdit.T1.physicalRollback");
                actionEditCheck(!attempt.provider.commitAttempted && attempt.provider.blockedCommits == 0,
                        "actionEdit.T1.noCommit");
                actionEditCheck(!org.springframework.transaction.support.TransactionSynchronizationManager
                        .isActualTransactionActive(), "actionEdit.T1.transactionClosed");
                // The physical rollback above must be observed BEFORE this defensive cleanup.
            } finally { connection.rollback(); }
            // Discard the failed persistence context. No query is attempted in the failed session.
        }
        // T2: a new JDBC connection and a new read-only transaction, never the failed session.
        ActionEditState restored = readCommittedActionEditState(config, fixture);
        verifyActionEditSameState(baseline, restored);
        actionEditCheck(restored.events().stream().noneMatch(row -> "ACAO_EDITADA".equals(row.path("tipo").asText())),
                "actionEdit.T2.noPartialEvent");
    }

    private static ActionEditState readCommittedActionEditState(Config config, ActionEditFixture fixture) throws Exception {
        try (Connection connection = openActionEditConnection(config)) {
            try {
                verifyDatabase(connection, config); // Confirms transaction_read_only=on in this transaction.
                return readActionEditState(connection, fixture);
            } finally { connection.rollback(); }
        }
    }

    private static ActionEditFixture createActionEditFixture(ActionEditHarness harness, boolean withControl) throws Exception {
        String marker = "IT action-edit " + UUID.randomUUID();
        harness.ledger.marker = marker;
        Cargo cargo = new Cargo();
        cargo.setNome(marker + " cargo");
        cargo.setNivel(NivelCargo.NAO_APLICAVEL);
        harness.em.persist(cargo);
        harness.ledger.add("cargo", cargo.getId());
        Equipe equipe = new Equipe();
        equipe.setNome(marker + " equipe");
        harness.em.persist(equipe);
        harness.ledger.add("equipe", equipe.getId());
        Usuario user = new Usuario();
        user.setNome(marker + " user");
        user.setEmail("action-edit-" + UUID.randomUUID() + "@example.invalid");
        user.setPerfilAcesso(PerfilUsuario.COLABORADOR);
        user.setAtivo(true);
        user.setCargo(cargo);
        user.setEquipeAtual(equipe);
        harness.em.persist(user);
        harness.em.flush();
        harness.ledger.add("usuario", user.getId());
        harness.identity.set(user.getId());
        var pdi = harness.plans.criar(new com.empresa.pdi.dto.CriarPdiRequest(marker,
                "Controlled action editing", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31)));
        var action = harness.actions.criar(pdi.id(), new com.empresa.pdi.dto.CriarAcaoPdiRequest(
                marker + " action", "Original objective", "Original result",
                LocalDate.of(2026, 2, 1), LocalDate.of(2026, 11, 30)));
        com.empresa.pdi.dto.AcaoPdiResponse control = withControl
                ? harness.actions.criar(pdi.id(), new com.empresa.pdi.dto.CriarAcaoPdiRequest(
                        marker + " control", null, null, null, null)) : null;
        return new ActionEditFixture(user.getId(), pdi.id(), pdi.versaoId(), action.id(), action.acaoVersaoId(),
                control == null ? null : control.id(), control == null ? null : control.acaoVersaoId());
    }

    private static com.empresa.pdi.dto.AtualizarAcaoPdiRequest actionEditRequest() {
        return new com.empresa.pdi.dto.AtualizarAcaoPdiRequest("IT edited action", "Edited objective",
                LocalDate.of(2026, 3, 1), LocalDate.of(2026, 10, 31), "Edited result");
    }

    private static com.empresa.pdi.dto.AtualizarAcaoPdiRequest actionEditRequestFromRow(JsonNode row) {
        String start = actionEditText(row, "inicio_planejado");
        String end = actionEditText(row, "fim_planejado");
        return new com.empresa.pdi.dto.AtualizarAcaoPdiRequest(actionEditText(row, "titulo"),
                actionEditText(row, "objetivo"), start == null ? null : LocalDate.parse(start),
                end == null ? null : LocalDate.parse(end), actionEditText(row, "resultado_esperado"));
    }

    private static String actionEditText(JsonNode row, String field) {
        actionEditCheck(row.has(field) && (row.get(field).isNull() || row.get(field).isTextual()),
                "actionEdit.read.contentField");
        return row.path(field).isNull() ? null : row.path(field).asText();
    }

    private static JsonNode actionEditJson(com.empresa.pdi.dto.AtualizarAcaoPdiRequest request) {
        return new ObjectMapper().createObjectNode().put("titulo", request.titulo())
                .put("objetivo", request.objetivo())
                .put("inicioPlanejado", request.inicioPlanejado() == null ? null : request.inicioPlanejado().toString())
                .put("fimPlanejado", request.fimPlanejado() == null ? null : request.fimPlanejado().toString())
                .put("resultadoEsperado", request.resultadoEsperado());
    }

    private static JsonNode actionEditContent(JsonNode row) { return actionEditJson(actionEditRequestFromRow(row)); }

    private static void verifyActionEditResponse(com.empresa.pdi.dto.AcaoPdiResponse response,
                                               ActionEditFixture fixture,
                                               com.empresa.pdi.dto.AtualizarAcaoPdiRequest request,
                                               ActionEditState before) {
        actionEditCheck(fixture.actionId().equals(response.id()) && fixture.contentId().equals(response.acaoVersaoId())
                && fixture.pdiId().equals(response.pdiId()) && fixture.versionId().equals(response.pdiVersaoId()),
                "actionEdit.response.identifiers");
        actionEditCheck(request.equals(new com.empresa.pdi.dto.AtualizarAcaoPdiRequest(response.titulo(),
                response.objetivo(), response.inicioPlanejado(), response.fimPlanejado(), response.resultadoEsperado())),
                "actionEdit.response.content");
        actionEditCheck(response.ordem().intValue() == before.content().path("ordem").asInt()
                && response.statusExecucao() == StatusExecucaoAcao.NAO_INICIADA, "actionEdit.response.state");
    }

    private static ActionEditState readActionEditState(Connection connection, ActionEditFixture fixture) throws Exception {
        JsonNode pdi = actionEditRow(connection, "pdi", fixture.pdiId());
        JsonNode version = actionEditRow(connection, "pdi_versao", fixture.versionId());
        JsonNode action = actionEditRow(connection, "acao_pdi", fixture.actionId());
        JsonNode content = actionEditRow(connection, "acao_pdi_versao", fixture.contentId());
        actionEditCheck(fixture.userId().toString().equals(pdi.path("usuario_id").asText())
                && pdi.path("versao_vigente_id").isNull() && "NAO_INICIADO".equals(pdi.path("status_execucao").asText())
                && version.path("numero").asInt() == 1 && "RASCUNHO".equals(version.path("status_aprovacao").asText())
                && version.path("submetida_em").isNull(), "actionEdit.persisted.eligibility");
        actionEditCheck(version.path("pdi_id").asLong() == fixture.pdiId()
                && action.path("pdi_id").asLong() == fixture.pdiId()
                && content.path("pdi_id").asLong() == fixture.pdiId()
                && content.path("pdi_versao_id").asLong() == fixture.versionId()
                && content.path("acao_pdi_id").asLong() == fixture.actionId()
                && "NAO_INICIADA".equals(action.path("status_execucao").asText()), "actionEdit.persisted.references");
        var events = new java.util.ArrayList<JsonNode>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT to_jsonb(e) FROM public.pdi_evento e WHERE pdi_id = ? ORDER BY id")) {
            statement.setLong(1, fixture.pdiId());
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) events.add(actionEditJsonMapper().readTree(rows.getString(1)));
            }
        }
        return new ActionEditState(pdi, version, action, content,
                fixture.controlId() == null ? null : actionEditRow(connection, "acao_pdi", fixture.controlId()),
                fixture.controlContentId() == null ? null : actionEditRow(connection, "acao_pdi_versao", fixture.controlContentId()),
                java.util.List.copyOf(events));
    }

    private static ObjectMapper actionEditJsonMapper() {
        return new ObjectMapper().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    }

    private static JsonNode actionEditRow(Connection connection, String table, Long id) throws Exception {
        actionEditCheck(Set.of("pdi", "pdi_versao", "acao_pdi", "acao_pdi_versao").contains(table),
                "actionEdit.read.allowedTable");
        try (PreparedStatement statement = connection.prepareStatement("SELECT to_jsonb(r) FROM public." + table + " r WHERE id = ?")) {
            statement.setLong(1, id);
            try (ResultSet rows = statement.executeQuery()) {
                actionEditCheck(rows.next(), "actionEdit.read.rowExists");
                JsonNode result = actionEditJsonMapper().readTree(rows.getString(1));
                actionEditCheck(!rows.next(), "actionEdit.read.uniqueRow");
                return result;
            }
        }
    }

    private static void verifyActionEditProtectedState(ActionEditState before, ActionEditState after) {
        actionEditCheck(jsonExactlyEquivalent(before.pdi(), after.pdi())
                && jsonExactlyEquivalent(before.version(), after.version())
                && jsonExactlyEquivalent(before.action(), after.action()), "actionEdit.integrity.identityAndLifecycle");
        var previous = (com.fasterxml.jackson.databind.node.ObjectNode) before.content().deepCopy();
        var current = (com.fasterxml.jackson.databind.node.ObjectNode) after.content().deepCopy();
        for (String field : java.util.List.of("titulo", "objetivo", "inicio_planejado", "fim_planejado", "resultado_esperado")) {
            previous.remove(field);
            current.remove(field);
        }
        actionEditCheck(jsonExactlyEquivalent(previous, current), "actionEdit.integrity.contentReferencesAndOrder");
        actionEditCheck(jsonExactlyEquivalent(before.control(), after.control())
                && jsonExactlyEquivalent(before.controlContent(), after.controlContent()), "actionEdit.integrity.unrelatedAction");
    }

    private static void verifyActionEditBaseline(ActionEditFixture fixture, ActionEditState state) {
        int additions = fixture.controlId() == null ? 1 : 2;
        actionEditCheck(state.events().size() == additions + 1, "actionEdit.baseline.eventCount");
        actionEditCheck(state.events().stream().filter(row -> "CRIACAO".equals(row.path("tipo").asText())
                && row.path("acao_pdi_id").isNull()).count() == 1, "actionEdit.baseline.creation");
        actionEditCheck(state.events().stream().filter(row -> "ACAO_ADICIONADA".equals(row.path("tipo").asText()))
                .count() == additions, "actionEdit.baseline.additions");
        actionEditCheck(state.events().stream().filter(row -> "ACAO_ADICIONADA".equals(row.path("tipo").asText())
                && row.path("acao_pdi_id").asLong() == fixture.actionId()).count() == 1, "actionEdit.baseline.targetAddition");
        if (fixture.controlId() != null) {
            actionEditCheck(state.events().stream().filter(row -> "ACAO_ADICIONADA".equals(row.path("tipo").asText())
                    && row.path("acao_pdi_id").asLong() == fixture.controlId()).count() == 1,
                    "actionEdit.baseline.controlAddition");
        }
        actionEditCheck(state.events().stream().allMatch(row -> !row.path("ocorrido_em").isNull()
                && fixture.userId().toString().equals(row.path("autor_id").asText())
                && row.path("pdi_id").asLong() == fixture.pdiId()
                && row.path("pdi_versao_id").asLong() == fixture.versionId()
                && row.path("snapshot").isNull()), "actionEdit.baseline.eventReferences");
    }

    private static void verifyActionEditSameState(ActionEditState before, ActionEditState after) {
        verifyActionEditProtectedState(before, after);
        actionEditCheck(jsonExactlyEquivalent(before.content(), after.content()), "actionEdit.unchanged.content");
        actionEditCheck(before.events().size() == after.events().size(), "actionEdit.unchanged.eventCount");
        for (int i = 0; i < before.events().size(); i++) {
            actionEditCheck(jsonExactlyEquivalent(before.events().get(i), after.events().get(i)), "actionEdit.unchanged.history");
        }
    }

    private static void verifyActionEditHistory(ActionEditHarness harness, ActionEditFixture fixture,
                                               ActionEditState before, ActionEditState after,
                                               com.empresa.pdi.dto.AtualizarAcaoPdiRequest request) throws Exception {
        actionEditCheck(after.events().size() == before.events().size() + 1, "actionEdit.history.oneNewEvent");
        for (int i = 0; i < before.events().size(); i++) {
            actionEditCheck(jsonExactlyEquivalent(before.events().get(i), after.events().get(i)), "actionEdit.history.previousIntact");
        }
        var edits = after.events().stream().filter(row -> "ACAO_EDITADA".equals(row.path("tipo").asText())).toList();
        actionEditCheck(edits.size() == 1, "actionEdit.history.exactType");
        JsonNode event = edits.get(0);
        actionEditCheck(fixture.userId().toString().equals(event.path("autor_id").asText())
                && event.path("pdi_id").asLong() == fixture.pdiId()
                && event.path("pdi_versao_id").asLong() == fixture.versionId()
                && event.path("acao_pdi_id").asLong() == fixture.actionId(), "actionEdit.history.references");
        actionEditCheck(event.path("componente_id").isNull() && event.path("submissao_id").isNull(),
                "actionEdit.history.noUnrelatedReferences");
        Set<String> previousKeys = new java.util.HashSet<>();
        Set<String> currentKeys = new java.util.HashSet<>();
        event.path("snapshot").fieldNames().forEachRemaining(previousKeys::add);
        event.path("dados").fieldNames().forEachRemaining(currentKeys::add);
        actionEditCheck(ACTION_EDIT_FIELDS.equals(previousKeys) && ACTION_EDIT_FIELDS.equals(currentKeys),
                "actionEdit.history.exactFields");
        actionEditCheck(jsonExactlyEquivalent(actionEditContent(before.content()), event.path("snapshot"))
                && jsonExactlyEquivalent(actionEditJson(request), event.path("dados")), "actionEdit.history.valuesAndNulls");
        PdiEvento loaded = harness.em.find(PdiEvento.class, event.path("id").asLong());
        actionEditCheck(harness.savedEditJpaTimestamp != null && loaded != null && loaded.getOcorridoEm() != null
                && loaded.getOcorridoEm().equals(java.time.OffsetDateTime.parse(event.path("ocorrido_em").asText()).toInstant()),
                "actionEdit.history.jpaTimestamp");
        actionEditCheck(loaded.getAcaoPdiVersao().getId().equals(fixture.contentId()), "actionEdit.history.jpaNavigation");
        try (PreparedStatement statement = harness.connection.prepareStatement(
                "SELECT pg_typeof(dados)::text, pg_typeof(snapshot)::text, jsonb_typeof(dados), jsonb_typeof(snapshot) "
                        + "FROM public.pdi_evento WHERE id = ?")) {
            statement.setLong(1, loaded.getId());
            try (ResultSet rows = statement.executeQuery()) {
                actionEditCheck(rows.next() && "jsonb".equals(rows.getString(1)) && "jsonb".equals(rows.getString(2))
                        && "object".equals(rows.getString(3)) && "object".equals(rows.getString(4)),
                        "actionEdit.history.physicalJsonb");
            }
        }
    }

    private static void verifyActionEditFixturesAbsent(Connection connection, ActionEditLedger ledger) throws SQLException {
        for (ActionEditRow row : ledger.rows) assertAbsent(connection, row.table(), row.id());
    }

    private record ActionEditFixture(UUID userId, Long pdiId, Long versionId, Long actionId, Long contentId,
                                     Long controlId, Long controlContentId) { }
    private record ActionEditState(JsonNode pdi, JsonNode version, JsonNode action, JsonNode content,
                                   JsonNode control, JsonNode controlContent, java.util.List<JsonNode> events) {
        @Override public String toString() { return "Action edit database snapshot [redacted]"; }
    }
    private record ActionEditRow(String table, Object id) { }
    private static final class ActionEditLedger {
        private final java.util.List<ActionEditRow> rows = new java.util.ArrayList<>();
        private String marker;
        void add(String table, Object id) {
            if (id == null) return;
            actionEditCheck(Set.of("cargo", "equipe", "usuario", "pdi", "pdi_versao", "acao_pdi",
                    "acao_pdi_versao", "pdi_evento").contains(table) && (id instanceof UUID || id instanceof Long),
                    "actionEdit.fixture.safeIdentifier");
            var row = new ActionEditRow(table, id);
            if (!rows.contains(row)) rows.add(row);
        }
    }

    private static final class ActionEditSql implements org.hibernate.resource.jdbc.spi.StatementInspector {
        private boolean tracking;
        private int writes;
        void start() { writes = 0; tracking = true; }
        @Override public String inspect(String sql) {
            String normalized = sql.stripLeading().toUpperCase(java.util.Locale.ROOT);
            if (tracking && (normalized.startsWith("INSERT ") || normalized.startsWith("UPDATE ")
                    || normalized.startsWith("DELETE "))) writes++;
            return sql; // Only counters are retained; no SQL or parameters are logged.
        }
    }

    private static final class ActionEditHarness implements AutoCloseable {
        final Connection connection;
        final ActionEditConnectionProvider provider;
        final ActionEditSql sql = new ActionEditSql();
        final ActionEditLedger ledger = new ActionEditLedger();
        final StandardServiceRegistry registry;
        final SessionFactory factory;
        final org.springframework.orm.jpa.JpaTransactionManager manager;
        final jakarta.persistence.EntityManager em;
        final java.util.concurrent.atomic.AtomicReference<UUID> identity = new java.util.concurrent.atomic.AtomicReference<>();
        final com.empresa.pdi.service.PdiService plans;
        final com.empresa.pdi.service.AcaoPdiService actions;
        ActionEditFixture failureTarget;
        boolean failureInjected;
        boolean updateObserved;
        java.time.Instant savedEditJpaTimestamp;

        ActionEditHarness(Connection connection, boolean fixtureCommitMode) {
            this.connection = connection;
            provider = new ActionEditConnectionProvider(connection, fixtureCommitMode);
            var mapper = new ObjectMapper().findAndRegisterModules()
                    .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
            StandardServiceRegistryBuilder builder = new StandardServiceRegistryBuilder();
            builder.getSettings().clear();
            builder.addService(ConnectionProvider.class, provider)
                    .applySetting("hibernate.dialect", "org.hibernate.dialect.PostgreSQLDialect")
                    .applySetting("hibernate.boot.allow_jdbc_metadata_access", false)
                    .applySetting("hibernate.temp.use_jdbc_metadata_defaults", false)
                    .applySetting("hibernate.hbm2ddl.auto", "none")
                    .applySetting("jakarta.persistence.schema-generation.database.action", "none")
                    .applySetting("jakarta.persistence.schema-generation.scripts.action", "none")
                    .applySetting("hibernate.default_schema", "public")
                    .applySetting("hibernate.show_sql", false)
                    .applySetting("hibernate.use_sql_comments", false)
                    .applySetting("hibernate.cache.use_second_level_cache", false)
                    .applySetting("hibernate.cache.use_query_cache", false)
                    .applySetting("hibernate.session_factory.statement_inspector", sql)
                    .applySetting("hibernate.type.json_format_mapper",
                            new org.hibernate.type.format.jackson.JacksonJsonFormatMapper(mapper));
            registry = builder.build();
            SessionFactory createdFactory = null;
            try {
                var sources = new MetadataSources(registry);
                Set<Class<?>> expected = Set.of(PDI.class, PdiVersao.class, AcaoPDI.class, AcaoPdiVersao.class,
                        PdiComponente.class, PdiComponenteVersao.class, EntregaSubmissao.class,
                        PdiEvento.class, Usuario.class, Cargo.class, Equipe.class);
                expected.forEach(sources::addAnnotatedClass);
                var metadata = sources.buildMetadata();
                actionEditCheck(expected.equals(metadata.getEntityBindings().stream()
                        .map(binding -> binding.getMappedClass()).collect(Collectors.toSet())), "actionEdit.bootstrap.entities");
                createdFactory = metadata.buildSessionFactory();
                factory = createdFactory;
                manager = new org.springframework.orm.jpa.JpaTransactionManager(factory);
                em = org.springframework.orm.jpa.SharedEntityManagerCreator.createSharedEntityManager(factory);
                var repositories = new org.springframework.data.jpa.repository.support.JpaRepositoryFactory(em);
                var users = repositories.getRepository(com.empresa.pdi.repository.UsuarioRepository.class);
                var pdis = repositories.getRepository(com.empresa.pdi.repository.PDIRepository.class);
                var versions = repositories.getRepository(com.empresa.pdi.repository.PdiVersaoRepository.class);
                var realEvents = repositories.getRepository(com.empresa.pdi.repository.PdiEventoRepository.class);
                var beans = new org.springframework.beans.factory.support.DefaultListableBeanFactory();
                beans.registerSingleton("actionEditIdentity",
                        (com.empresa.pdi.security.UsuarioAutenticadoProvider) identity::get);
                com.empresa.pdi.repository.PdiEventoRepository writer = new com.empresa.pdi.repository.PdiEventoRepository() {
                    @Override public <S extends PdiEvento> S saveAndFlush(S event) {
                        ledger.add("pdi", event.getPdiId());
                        ledger.add("pdi_versao", event.getPdiVersaoId());
                        if (event.getAcaoPdiId() != null) {
                            ledger.add("acao_pdi", event.getAcaoPdiId());
                            ledger.add("acao_pdi_versao", event.getAcaoPdiVersao().getId());
                        }
                        if (failureTarget != null && "ACAO_EDITADA".equals(event.getTipo())) {
                            actionEditCheck(!failureInjected && failureTarget.pdiId().equals(event.getPdiId())
                                    && failureTarget.versionId().equals(event.getPdiVersaoId())
                                    && failureTarget.actionId().equals(event.getAcaoPdiId())
                                    && failureTarget.contentId().equals(event.getAcaoPdiVersao().getId()),
                                    "actionEdit.failure.onlyNewTargetEvent");
                            try {
                                JsonNode persisted = actionEditRow(connection, "acao_pdi_versao", failureTarget.contentId());
                                updateObserved = jsonExactlyEquivalent(actionEditJson(actionEditRequest()), actionEditContent(persisted));
                                actionEditCheck(updateObserved, "actionEdit.failure.flushedContent");
                            } catch (Exception failure) {
                                throw new IllegalStateException("Action edit flush verification failed", failure);
                            }
                            org.springframework.test.util.ReflectionTestUtils.setField(event, "tipo", "");
                            failureInjected = true;
                        }
                        S saved = realEvents.saveAndFlush(event);
                        if ("ACAO_EDITADA".equals(saved.getTipo())) savedEditJpaTimestamp = saved.getOcorridoEm();
                        ledger.add("pdi_evento", saved.getId());
                        return saved;
                    }
                };
                var eventMapper = new ObjectMapper().findAndRegisterModules();
                plans = proxy(new com.empresa.pdi.service.PdiService(pdis, versions,
                        repositories.getRepository(com.empresa.pdi.repository.AcaoPdiVersaoRepository.class),
                        writer, users,
                        beans.getBeanProvider(com.empresa.pdi.security.UsuarioAutenticadoProvider.class), eventMapper));
                actions = proxy(new com.empresa.pdi.service.AcaoPdiService(
                        repositories.getRepository(com.empresa.pdi.repository.AcaoPDIRepository.class),
                        repositories.getRepository(com.empresa.pdi.repository.AcaoPdiVersaoRepository.class),
                        pdis, versions, writer, users,
                        beans.getBeanProvider(com.empresa.pdi.security.UsuarioAutenticadoProvider.class), eventMapper));
            } catch (RuntimeException | Error failure) {
                try { if (createdFactory != null) createdFactory.close(); }
                finally { StandardServiceRegistryBuilder.destroy(registry); }
                throw failure;
            }
        }

        @SuppressWarnings("unchecked")
        private <T> T proxy(T target) {
            var interceptor = new org.springframework.transaction.interceptor.TransactionInterceptor();
            interceptor.setTransactionManager(manager);
            interceptor.setTransactionAttributeSource(new org.springframework.transaction.annotation.AnnotationTransactionAttributeSource());
            var proxy = new org.springframework.aop.framework.ProxyFactory(target);
            proxy.setProxyTargetClass(true);
            proxy.addAdvice(interceptor);
            return (T) proxy.getProxy();
        }

        @Override public void close() {
            try { factory.close(); } finally { StandardServiceRegistryBuilder.destroy(registry); }
        }
    }

    private static final class ActionEditConnectionProvider implements ConnectionProvider {
        private final Connection guarded;
        private final boolean fixtureCommitMode;
        private boolean armed;
        private boolean commitAttempted;
        private boolean commitConfirmed;
        private int blockedCommits;
        private int rollbacks;

        ActionEditConnectionProvider(Connection connection, boolean fixtureCommitMode) {
            this.fixtureCommitMode = fixtureCommitMode;
            guarded = (Connection) java.lang.reflect.Proxy.newProxyInstance(
                    PdiServicePersistenceIT.class.getClassLoader(), new Class<?>[]{Connection.class},
                    (proxy, method, arguments) -> {
                        if (method.getName().equals("commit")) {
                            if (!fixtureCommitMode || !armed || commitAttempted
                                    || !"true".equals(System.getenv(ACTION_EDIT_COMMIT_FLAG))) {
                                blockedCommits++;
                                throw new SQLException("Commit is forbidden outside the one-shot fixture gate");
                            }
                            armed = false;
                            commitAttempted = true; // Never retry, including an uncertain network result.
                            connection.commit();
                            commitConfirmed = true;
                            return null;
                        }
                        if (method.getName().equals("setAutoCommit") && Boolean.TRUE.equals(arguments[0])) {
                            throw new SQLException("Autocommit is forbidden in action edit tests");
                        }
                        if (method.getName().equals("close")) return null;
                        if (method.getName().equals("unwrap")) {
                            if (((Class<?>) arguments[0]).isInstance(proxy)) return proxy;
                            throw new SQLException("Raw connection unwrap is forbidden");
                        }
                        if (method.getName().equals("isWrapperFor")) return ((Class<?>) arguments[0]).isInstance(proxy);
                        try {
                            Object result = method.invoke(connection, arguments);
                            if (method.getName().equals("rollback")) rollbacks++;
                            return result;
                        } catch (java.lang.reflect.InvocationTargetException failure) {
                            throw failure.getCause();
                        }
                    });
        }

        void armFixtureCommit() {
            actionEditCheck(fixtureCommitMode && !armed && !commitAttempted
                    && "true".equals(System.getenv(ACTION_EDIT_COMMIT_FLAG)), "actionEdit.T0.explicitCommitGate");
            armed = true;
        }
        @Override public Connection getConnection() { return guarded; }
        @Override public void closeConnection(Connection connection) { /* Only the physical owner closes it. */ }
        @Override public boolean supportsAggressiveRelease() { return false; }
        @Override public boolean isUnwrappableAs(Class<?> type) { return type.isInstance(this); }
        @Override public <T> T unwrap(Class<T> type) {
            if (type.isInstance(this)) return type.cast(this);
            throw new IllegalArgumentException("Unsupported unwrap");
        }
    }

    private record Config(String host, String database, String user, String password,
                          String projectId, String branchId, String endpointId, String apiKey) {
        String url() { return "jdbc:postgresql://" + host + ":5432/" + database; }
        @Override public String toString() { return "PDI integration configuration [redacted]"; }
    }

}
