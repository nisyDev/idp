package com.empresa.pdi.integration;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import com.empresa.pdi.dto.CriarAcaoPdiRequest;
import com.empresa.pdi.dto.CriarPdiRequest;
import com.empresa.pdi.enums.NivelCargo;
import com.empresa.pdi.enums.PerfilUsuario;
import com.empresa.pdi.enums.StatusAprovacaoPdi;
import com.empresa.pdi.enums.StatusExecucaoPdi;
import com.empresa.pdi.model.*;
import com.empresa.pdi.repository.*;
import com.empresa.pdi.security.UsuarioAutenticadoProvider;
import com.empresa.pdi.service.AcaoPdiService;
import com.empresa.pdi.service.PdiService;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import org.hibernate.SessionFactory;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistry;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.hibernate.engine.jdbc.connections.spi.ConnectionProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.slf4j.LoggerFactory;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.http.HttpStatus;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.web.server.ResponseStatusException;

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
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.CRC32;

import static org.junit.jupiter.api.Assertions.*;

/**
 * G7.8 - separate, explicitly gated PostgreSQL integration scenarios for initial PDI submission.
 * NEVER commits; no DDL, migrations, data cleanup by DELETE, or Spring Boot startup.
 * All fixture rows are created within a single outer transaction and rolled back.
 * The test itself is NOT an authorization to run against Neon.
 */
@EnabledIfEnvironmentVariable(named = "TALENTSYNK_PDI_SUBMISSAO_IT_ENABLED", matches = "true")
class PdiSubmissaoPersistenceIT {
    private static final String ENABLE = "TALENTSYNK_PDI_SUBMISSAO_IT_ENABLED";
    private static final String PREFIX = "TALENTSYNK_PDI_SERVICE_IT_";
    private static final String APPROVED_BRANCH = "test-pdi-v2";

    private enum Case { VALID, WITHOUT_ACTION, WITHOUT_MANAGER, INACTIVE_MANAGER }

    @Test void submittedPdiAndEventRollbackCompletely() { run(Case.VALID); }
    @Test void noActionCannotBeSubmitted() { run(Case.WITHOUT_ACTION); }
    @Test void noManagerCannotBeSubmitted() { run(Case.WITHOUT_MANAGER); }
    @Test void inactiveManagerCannotReceiveSubmission() { run(Case.INACTIVE_MANAGER); }

    private void run(Case scenario) {
        org.junit.jupiter.api.Assumptions.assumeTrue("true".equals(System.getenv(ENABLE)));
        Logger hibernate = (Logger) LoggerFactory.getLogger("org.hibernate");
        Level oldHibernate = hibernate.getLevel();
        java.util.logging.Logger jdbcLogger = java.util.logging.Logger.getLogger("org.postgresql");
        java.util.logging.Level oldJdbc = jdbcLogger.getLevel();
        String stage = "configuration";
        try {
            hibernate.setLevel(Level.OFF);
            jdbcLogger.setLevel(java.util.logging.Level.OFF);
            Config cfg = configuration();
            verifyNeonIdentity(cfg);
            Properties props = new Properties();
            props.setProperty("user", cfg.user());
            props.setProperty("password", cfg.password());
            props.setProperty("sslmode", "verify-full");
            props.setProperty("sslfactory", "org.postgresql.ssl.DefaultJavaSSLFactory");
            props.setProperty("connectTimeout", "15");
            props.setProperty("socketTimeout", "30");
            props.setProperty("readOnlyMode", "transaction");
            props.setProperty("options", "-c search_path=public -c statement_timeout=15000 -c lock_timeout=5000");
            stage = "read-only database preflight";
            try (Connection connection = DriverManager.getConnection(cfg.url(), props)) {
                connection.setReadOnly(true);
                connection.setAutoCommit(false);
                try {
                    verifyDatabase(connection, cfg);
                    verifyMigrations(connection);
                    verifyPrivileges(connection);
                } finally {
                    connection.rollback();
                }
                stage = "Neon identity recheck";
                verifyNeonIdentity(cfg);
                stage = "transactional submission and rollback";
                execute(connection, scenario);
            }
        } catch (Exception | AssertionError failure) {
            // JDBC/Hibernate exceptions may contain connection parameters or payloads.
            // Never attach raw exceptions, messages, user IDs, SQL, or secrets to reports.
            throw new AssertionError("G7.8 failed; case=" + scenario.name()
                    + "; stage=" + stage + "; category=" + failure.getClass().getSimpleName());
        } finally {
            hibernate.setLevel(oldHibernate);
            jdbcLogger.setLevel(oldJdbc);
        }
    }

    private static void execute(Connection connection, Case scenario) throws Exception {
        StandardServiceRegistry registry = null;
        Map<String, Object> ids = new LinkedHashMap<>();
        List<Long> eventIds = new ArrayList<>();
        try {
            var mapper = new ObjectMapper().findAndRegisterModules()
                    .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
            StandardServiceRegistryBuilder builder = new StandardServiceRegistryBuilder();
            builder.getSettings().clear();
            builder.applySetting("hibernate.type.json_format_mapper",
                    new org.hibernate.type.format.jackson.JacksonJsonFormatMapper(mapper));
            builder.addService(ConnectionProvider.class, new NoCommitConnectionProvider(connection))
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
            Set<Class<?>> entities = Set.of(PDI.class, PdiVersao.class, AcaoPDI.class,
                    AcaoPdiVersao.class, PdiComponente.class, PdiComponenteVersao.class,
                    EntregaSubmissao.class, PdiEvento.class, Usuario.class, Cargo.class, Equipe.class);
            entities.forEach(sources::addAnnotatedClass);
            var metadata = sources.buildMetadata();
            assertEquals(entities, metadata.getEntityBindings().stream()
                    .map(binding -> binding.getMappedClass()).collect(java.util.stream.Collectors.toSet()));
            try (SessionFactory factory = metadata.buildSessionFactory()) {
                connection.setReadOnly(false);
                JpaTransactionManager txManager = new JpaTransactionManager(factory);
                EntityManager em = SharedEntityManagerCreator.createSharedEntityManager(factory);
                var repositories = new org.springframework.data.jpa.repository.support.JpaRepositoryFactory(em);
                UsuarioRepository users = repositories.getRepository(UsuarioRepository.class);
                PDIRepository plans = repositories.getRepository(PDIRepository.class);
                PdiVersaoRepository versions = repositories.getRepository(PdiVersaoRepository.class);
                AcaoPDIRepository actions = repositories.getRepository(AcaoPDIRepository.class);
                AcaoPdiVersaoRepository actionVersions = repositories.getRepository(AcaoPdiVersaoRepository.class);
                PdiEventoRepository events = repositories.getRepository(PdiEventoRepository.class);
                AtomicReference<UUID> identity = new AtomicReference<>();
                var beans = new org.springframework.beans.factory.support.DefaultListableBeanFactory();
                beans.registerSingleton("testIdentity", (UsuarioAutenticadoProvider) identity::get);
                var provider = beans.getBeanProvider(UsuarioAutenticadoProvider.class);
                var service = transactionalProxy(new PdiService(plans, versions, actionVersions,
                        events, users, provider, mapper), txManager);
                var actionService = transactionalProxy(new AcaoPdiService(actions, actionVersions,
                        plans, versions, events, users, provider, mapper), txManager);

                TransactionStatus outer = txManager.getTransaction(
                        new org.springframework.transaction.support.DefaultTransactionDefinition());
                try {
                    String suffix = UUID.randomUUID().toString();
                    Cargo cargo = new Cargo();
                    cargo.setNome("IT G7.8 cargo " + suffix);
                    cargo.setNivel(NivelCargo.NAO_APLICAVEL);
                    em.persist(cargo);
                    Equipe team = new Equipe();
                    team.setNome("IT G7.8 team " + suffix);
                    em.persist(team);
                    Usuario manager = null;
                    if (scenario != Case.WITHOUT_MANAGER) {
                        manager = new Usuario();
                        manager.setNome("IT G7.8 manager " + suffix);
                        manager.setEmail("g78-manager-" + suffix + "@example.invalid");
                        manager.setPerfilAcesso(PerfilUsuario.GESTOR);
                        manager.setCargo(cargo);
                        manager.setEquipeAtual(team);
                        manager.setAtivo(scenario != Case.INACTIVE_MANAGER);
                        em.persist(manager);
                    }
                    Usuario owner = new Usuario();
                    owner.setNome("IT G7.8 owner " + suffix);
                    owner.setEmail("g78-owner-" + suffix + "@example.invalid");
                    owner.setPerfilAcesso(PerfilUsuario.COLABORADOR);
                    owner.setCargo(cargo);
                    owner.setEquipeAtual(team);
                    owner.setGestor(manager);
                    em.persist(owner);
                    em.flush();
                    ids.put("cargo", cargo.getId());
                    ids.put("equipe", team.getId());
                    if (manager != null) ids.put("usuario_manager", manager.getId());
                    ids.put("usuario_owner", owner.getId());
                    identity.set(owner.getId());

                    var pdi = service.criar(new CriarPdiRequest("IT G7.8 PDI " + suffix,
                            "Controlled submission", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31)));
                    ids.put("pdi", pdi.id());
                    ids.put("pdi_versao", pdi.versaoId());
                    if (scenario != Case.WITHOUT_ACTION) {
                        var action = actionService.criar(pdi.id(), new CriarAcaoPdiRequest(
                                "IT G7.8 action", "Objective", "Evidence",
                                LocalDate.of(2026, 2, 1), LocalDate.of(2026, 11, 30)));
                        ids.put("acao_pdi", action.id());
                        ids.put("acao_pdi_versao", action.acaoVersaoId());
                    }
                    // Read the initial fixture before testing a rejected operation: a
                    // transactional service exception may mark the outer transaction rollback-only.
                    em.flush();
                    em.clear();
                    PdiVersao initial = em.find(PdiVersao.class, pdi.versaoId());
                    assertEquals(StatusAprovacaoPdi.RASCUNHO, initial.getStatusAprovacao());
                    assertNull(initial.getSubmetidaEm());
                    if (scenario == Case.VALID) {
                        var submitted = service.submeter(pdi.id());
                        assertEquals(StatusAprovacaoPdi.AGUARDANDO_APROVACAO, submitted.statusAprovacao());
                        assertEquals(manager.getId(), submitted.gestorNaSubmissaoId());
                        assertNotNull(submitted.submetidaEm());
                        assertNull(submitted.versaoVigenteId());
                        var listed = service.listarSubmetidos();
                        assertEquals(1, listed.stream().filter(v -> pdi.id().equals(v.id())).count());
                    }
                    em.flush();
                    em.clear();
                    PDI stored = em.find(PDI.class, pdi.id());
                    PdiVersao version = em.find(PdiVersao.class, pdi.versaoId());
                    assertEquals(StatusExecucaoPdi.NAO_INICIADO, stored.getStatusExecucao());
                    assertNull(stored.getVersaoVigenteId());
                    assertNotNull(version);
                    if (scenario == Case.VALID) {
                        assertEquals(StatusAprovacaoPdi.AGUARDANDO_APROVACAO, version.getStatusAprovacao());
                        assertNotNull(version.getSubmetidaEm());
                        assertEquals(manager.getId(), version.getGestorNaSubmissao().getId());
                    } else {
                        assertEquals(StatusAprovacaoPdi.RASCUNHO, version.getStatusAprovacao());
                        assertNull(version.getSubmetidaEm());
                        assertNull(version.getGestorNaSubmissao());
                    }
                    var history = em.createQuery("select e from PdiEvento e where e.pdiId = :id", PdiEvento.class)
                            .setParameter("id", pdi.id()).getResultList();
                    long submissions = history.stream().filter(e -> "PDI_SUBMETIDO".equals(e.getTipo())).count();
                    assertEquals(scenario == Case.VALID ? 1L : 0L, submissions);
                    for (PdiEvento event : history) {
                        eventIds.add(event.getId());
                        if ("PDI_SUBMETIDO".equals(event.getTipo())) {
                            assertEquals(owner.getId(), event.getAutor().getId());
                            assertEquals(pdi.id(), event.getPdiId());
                            assertEquals(pdi.versaoId(), event.getPdiVersaoId());
                            assertEquals(StatusAprovacaoPdi.AGUARDANDO_APROVACAO.name(),
                                    event.getDados().path("statusAprovacao").asText());
                            assertEquals(StatusAprovacaoPdi.RASCUNHO.name(),
                                    event.getSnapshot().path("statusAprovacao").asText());
                            assertEquals(manager.getId().toString(),
                                    event.getDados().path("gestorNaSubmissaoId").asText());
                        }
                    }
                    if (scenario == Case.VALID) {
                        // A second owner cannot discover someone else's submitted PDI.
                        Usuario outsider = new Usuario();
                        outsider.setNome("IT G7.8 outsider " + suffix);
                        outsider.setEmail("g78-outsider-" + suffix + "@example.invalid");
                        outsider.setPerfilAcesso(PerfilUsuario.COLABORADOR);
                        outsider.setCargo(cargo);
                        outsider.setEquipeAtual(team);
                        em.persist(outsider);
                        em.flush();
                        ids.put("usuario_outsider", outsider.getId());
                        identity.set(outsider.getId());
                        assertTrue(service.listarSubmetidos().stream()
                                .noneMatch(v -> pdi.id().equals(v.id())));
                        identity.set(owner.getId());
                        assertThrows(com.empresa.pdi.exception.PdiRascunhoNaoEditavelException.class,
                                () -> service.submeter(pdi.id()));
                    } else {
                        ResponseStatusException rejected = assertThrows(ResponseStatusException.class,
                                () -> service.submeter(pdi.id()));
                        assertEquals(HttpStatus.CONFLICT, rejected.getStatusCode());
                    }
                } finally {
                    // The only possible termination for this fixture is rollback.
                    txManager.rollback(outer);
                }
                connection.setReadOnly(true);
                try {
                    for (var entry : ids.entrySet()) {
                        String table = switch (entry.getKey()) {
                            case "usuario_manager", "usuario_owner", "usuario_outsider" -> "usuario";
                            default -> entry.getKey();
                        };
                        assertAbsent(connection, table, entry.getValue());
                    }
                    for (Long eventId : eventIds) assertAbsent(connection, "pdi_evento", eventId);
                    if (ids.containsKey("pdi")) {
                        try (PreparedStatement statement = connection.prepareStatement(
                                "SELECT COUNT(*) FROM public.pdi_evento WHERE pdi_id = ?")) {
                            statement.setObject(1, ids.get("pdi"));
                            try (ResultSet rows = statement.executeQuery()) {
                                assertTrue(rows.next());
                                assertEquals(0L, rows.getLong(1));
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

    @SuppressWarnings("unchecked")
    private static <T> T transactionalProxy(T target, JpaTransactionManager txManager) {
        var interceptor = new TransactionInterceptor();
        interceptor.setTransactionManager(txManager);
        interceptor.setTransactionAttributeSource(
                new org.springframework.transaction.annotation.AnnotationTransactionAttributeSource());
        var factory = new ProxyFactory(target);
        factory.setProxyTargetClass(true);
        factory.addAdvice(interceptor);
        return (T) factory.getProxy();
    }

    private static final class NoCommitConnectionProvider implements ConnectionProvider {
        private final Connection guarded;
        NoCommitConnectionProvider(Connection physical) {
            guarded = (Connection) java.lang.reflect.Proxy.newProxyInstance(
                    PdiSubmissaoPersistenceIT.class.getClassLoader(), new Class<?>[]{Connection.class},
                    (proxy, method, args) -> {
                        if (method.getName().equals("commit")
                                || (method.getName().equals("setAutoCommit") && Boolean.TRUE.equals(args[0]))) {
                            throw new SQLException("Commit is forbidden in G7.8");
                        }
                        if (method.getName().equals("close")) return null;
                        if (method.getName().equals("unwrap")) {
                            if (((Class<?>) args[0]).isInstance(proxy)) return proxy;
                            throw new SQLException("Raw connection unwrap forbidden");
                        }
                        if (method.getName().equals("isWrapperFor")) return ((Class<?>) args[0]).isInstance(proxy);
                        try {
                            return method.invoke(physical, args);
                        } catch (java.lang.reflect.InvocationTargetException ex) {
                            throw ex.getCause();
                        }
                    });
        }
        @Override public Connection getConnection() { return guarded; }
        @Override public void closeConnection(Connection connection) { }
        @Override public boolean supportsAggressiveRelease() { return false; }
        @Override public boolean isUnwrappableAs(Class<?> type) { return type.isInstance(this); }
        @Override public <T> T unwrap(Class<T> type) {
            if (type.isInstance(this)) return type.cast(this);
            throw new IllegalArgumentException("Unsupported unwrap");
        }
    }

    private static void assertAbsent(Connection connection, String table, Object id) throws SQLException {
        if (id == null) throw new AssertionError("Fixture ID was not captured");
        // Fixed internal table names only; not user input.
        if (!Set.of("cargo", "equipe", "usuario", "pdi", "pdi_versao", "acao_pdi",
                "acao_pdi_versao", "pdi_evento").contains(table)) {
            throw new IllegalArgumentException("Unexpected table name");
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT EXISTS (SELECT 1 FROM public." + table + " WHERE id = ?)")) {
            statement.setObject(1, id);
            try (ResultSet rows = statement.executeQuery()) {
                assertTrue(rows.next());
                assertFalse(rows.getBoolean(1), "Fixture row must not survive rollback");
            }
        }
    }

    private static Config configuration() {
        String host = required("HOST");
        String database = required("DATABASE");
        String user = required("USER");
        if (!"pdi_db".equals(database) || !"talentsynk_test_runner".equals(user))
            throw new IllegalStateException("Unexpected database or principal");
        if (!host.matches("[a-z0-9.-]+\\.neon\\.tech") || host.contains("-pooler")
                || !database.matches("[A-Za-z0-9_-]+")) throw new IllegalStateException("Invalid host");
        if (!"public".equals(required("SCHEMA"))) throw new IllegalStateException("Unexpected schema");
        return new Config(host, database, user, required("PASSWORD"),
                identifier("PROJECT_ID"), identifier("BRANCH_ID"), identifier("ENDPOINT_ID"),
                required("NEON_API_KEY"));
    }

    private static String required(String name) {
        String value = System.getenv(PREFIX + name);
        if (value == null || value.isBlank()) throw new IllegalStateException("Missing protected setting: " + name);
        return value;
    }
    private static String identifier(String name) {
        String value = required(name);
        if (!value.matches("[a-z0-9-]{1,100}")) throw new IllegalStateException("Invalid resource identifier");
        return value;
    }
    private static void verifyNeonIdentity(Config cfg) throws Exception {
        try (HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NEVER).build()) {
            JsonNode branch = api(client, cfg, "/branches/" + cfg.branchId()).path("branch");
            if (!cfg.branchId().equals(branch.path("id").asText())
                    || !cfg.projectId().equals(branch.path("project_id").asText())
                    || !APPROVED_BRANCH.equals(branch.path("name").asText())
                    || !branch.path("default").isBoolean() || branch.path("default").asBoolean()
                    || !branch.path("protected").isBoolean() || branch.path("protected").asBoolean()) {
                throw new IllegalStateException("Unverified or default Neon branch");
            }
            JsonNode endpoint = api(client, cfg, "/endpoints/" + cfg.endpointId()).path("endpoint");
            if (!cfg.endpointId().equals(endpoint.path("id").asText())
                    || !cfg.branchId().equals(endpoint.path("branch_id").asText())
                    || !cfg.projectId().equals(endpoint.path("project_id").asText())
                    || !cfg.host().equals(endpoint.path("host").asText())
                    || !"read_write".equals(endpoint.path("type").asText())) {
                throw new IllegalStateException("Unverified Neon endpoint");
            }
        }
    }
    private static JsonNode api(HttpClient client, Config cfg, String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(
                    URI.create("https://console.neon.tech/api/v2/projects/" + cfg.projectId() + path))
                .timeout(Duration.ofSeconds(20))
                .header("Authorization", "Bearer " + cfg.apiKey())
                .header("Accept", "application/json").GET().build();
        var response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) throw new IllegalStateException("Neon identity API unavailable");
        return new ObjectMapper().readTree(response.body());
    }
    private static void verifyDatabase(Connection connection, Config cfg) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(
                "SELECT current_database(), current_schema(), current_user, session_user, current_setting('transaction_read_only')")) {
            assertTrue(rows.next());
            assertEquals(cfg.database(), rows.getString(1));
            assertEquals("public", rows.getString(2));
            assertEquals(cfg.user(), rows.getString(3));
            assertEquals(cfg.user(), rows.getString(4));
            assertEquals("on", rows.getString(5));
        }
    }
    private static void verifyPrivileges(Connection connection) throws SQLException {
        // Read-only preflight; fail closed if the isolated test principal cannot operate on fixture tables.
        Map<String, String> required = Map.of(
                "cargo", "INSERT", "equipe", "INSERT", "usuario", "INSERT,UPDATE",
                "pdi", "INSERT,UPDATE", "pdi_versao", "INSERT,UPDATE", "acao_pdi", "INSERT",
                "acao_pdi_versao", "INSERT", "pdi_evento", "INSERT");
        for (var entry : required.entrySet()) {
            for (String privilege : entry.getValue().split(",")) {
                try (PreparedStatement statement = connection.prepareStatement(
                        "SELECT has_table_privilege(current_user, ?, ?)")) {
                    statement.setString(1, "public." + entry.getKey());
                    statement.setString(2, privilege);
                    try (ResultSet rows = statement.executeQuery()) {
                        assertTrue(rows.next());
                        assertTrue(rows.getBoolean(1),
                                "Missing fixture privilege: " + entry.getKey() + "." + privilege);
                    }
                }
            }
        }
    }
    private static void verifyMigrations(Connection connection) throws Exception {
        Map<String, String> scripts = Map.of(
                "1", "V1__create_core_tables.sql",
                "2", "V2__create_pdi_initial_flow.sql",
                "3", "V3__create_usuario_credencial_local.sql");
        Set<String> found = new HashSet<>();
        try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(
                "SELECT version, script, checksum, success FROM public.flyway_schema_history ORDER BY installed_rank")) {
            while (rows.next()) {
                String version = rows.getString(1);
                assertTrue(scripts.containsKey(version), "Unexpected Flyway migration");
                assertTrue(found.add(version), "Duplicate migration");
                assertEquals(scripts.get(version), rows.getString(2));
                int checksum = rows.getInt(3);
                assertFalse(rows.wasNull());
                assertEquals(localChecksum(scripts.get(version)), checksum);
                if ("1".equals(version)) assertEquals(467754627, checksum);
                assertTrue(rows.getBoolean(4));
            }
        }
        assertEquals(scripts.keySet(), found);
    }
    private static int localChecksum(String name) throws Exception {
        CRC32 crc = new CRC32();
        try (var stream = PdiSubmissaoPersistenceIT.class.getResourceAsStream("/db/migration/" + name)) {
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
    private record Config(String host, String database, String user, String password,
                          String projectId, String branchId, String endpointId, String apiKey) {
        String url() { return "jdbc:postgresql://" + host + ":5432/" + database; }
    }
}
