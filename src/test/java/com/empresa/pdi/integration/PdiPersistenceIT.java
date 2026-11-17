package com.empresa.pdi.integration;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import com.empresa.pdi.enums.NivelCargo;
import com.empresa.pdi.enums.PerfilUsuario;
import com.empresa.pdi.enums.StatusAprovacaoPdi;
import com.empresa.pdi.enums.StatusExecucaoPdi;
import com.empresa.pdi.model.*;
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
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.slf4j.LoggerFactory;

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

/** Explicit-only PostgreSQL test. Never commits, migrates or boots Spring. */
@EnabledIfEnvironmentVariable(named = "TALENTSYNK_PDI_PERSISTENCE_ENABLED", matches = "true")
class PdiPersistenceIT {
    private static final String PREFIX = "TALENTSYNK_PDI_IT_";
    private static final String APPROVED_BRANCH = "test-pdi-v2";

    @Test
    void persistPdiAndVersionThenRollback() {
        org.junit.jupiter.api.Assumptions.assumeTrue(
                "true".equals(System.getenv("TALENTSYNK_PDI_PERSISTENCE_ENABLED")),
                "PDI persistence test requires explicit enablement."
        );
        // Keep SQL errors, connection settings and possible payloads out of test reports.
        Logger hibernate = (Logger) LoggerFactory.getLogger("org.hibernate");
        Level previousLevel = hibernate.getLevel();
        java.util.logging.Logger jdbc = java.util.logging.Logger.getLogger("org.postgresql");
        java.util.logging.Level previousJdbc = jdbc.getLevel();
        String stage = "external configuration and Neon identity";
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
                runScenario(connection);
            }
        } catch (Exception | AssertionError failure) {
            // Do not attach raw exceptions: JDBC/Hibernate messages may contain secrets/data.
            throw new AssertionError("PDI persistence test failed at: " + stage
                    + "; category=" + failure.getClass().getSimpleName(),
                    sanitizedCauseChain(failure));
        } finally {
            hibernate.setLevel(previousLevel);
            jdbc.setLevel(previousJdbc);
        }
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

    private static void runScenario(Connection connection) throws Exception {
        StandardServiceRegistry registry = null;
        UUID cargoId = null;
        UUID equipeId = null;
        UUID usuarioId = null;
        Long pdiId = null;
        Long versionId = null;
        try {
            StandardServiceRegistryBuilder builder = new StandardServiceRegistryBuilder();
            builder.getSettings().clear();
            builder.addService(ConnectionProvider.class, new NoConnections())
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
            var metadata = new MetadataSources(registry)
                    .addAnnotatedClass(PDI.class).addAnnotatedClass(PdiVersao.class)
                    .addAnnotatedClass(Usuario.class).addAnnotatedClass(Cargo.class)
                    .addAnnotatedClass(Equipe.class).buildMetadata();
            assertEquals(Set.of(PDI.class.getName(), PdiVersao.class.getName(), Usuario.class.getName(),
                            Cargo.class.getName(), Equipe.class.getName()),
                    metadata.getEntityBindings().stream().map(binding -> binding.getClassName())
                            .collect(Collectors.toSet()));
            try (SessionFactory factory = metadata.buildSessionFactory()) {
                connection.setReadOnly(false);
                try (Session session = factory.withOptions().connection(connection).openSession()) {
                    Transaction transaction = session.beginTransaction();
                    try {
                        String suffix = UUID.randomUUID().toString();
                        Cargo cargo = new Cargo();
                        cargo.setNome("IT cargo " + suffix);
                        cargo.setNivel(NivelCargo.NAO_APLICAVEL);
                        session.persist(cargo);
                        Equipe equipe = new Equipe();
                        equipe.setNome("IT equipe " + suffix);
                        session.persist(equipe);
                        Usuario usuario = new Usuario();
                        usuario.setNome("IT usuario " + suffix);
                        usuario.setEmail("pdi-it-" + suffix + "@example.invalid");
                        usuario.setPerfilAcesso(PerfilUsuario.COLABORADOR);
                        usuario.setCargo(cargo);
                        usuario.setEquipeAtual(equipe);
                        session.persist(usuario);
                        session.flush();
                        cargoId = cargo.getId();
                        equipeId = equipe.getId();
                        usuarioId = usuario.getId();

                        PDI pdi = new PDI();
                        pdi.setUsuario(usuario);
                        session.persist(pdi);
                        session.flush();
                        pdiId = pdi.getId();
                        session.clear();
                        pdi = session.find(PDI.class, pdiId);
                        assertNull(pdi.getVersaoVigenteId());
                        assertNull(pdi.getVersaoVigente());

                        PdiVersao version = new PdiVersao();
                        version.setPdi(pdi);
                        version.setNumero(1);
                        version.setTitulo("IT PDI " + suffix);
                        version.setObjetivo("Verificar persistência isolada");
                        version.setInicioPlanejado(LocalDate.of(2026, 1, 1));
                        version.setFimPlanejado(LocalDate.of(2026, 12, 31));
                        // No approval flow is simulated: this is a physical FK mapping check.
                        session.persist(version);
                        session.flush();
                        versionId = version.getId();
                        Long oldLockVersion = pdi.getLockVersion();
                        pdi.setVersaoVigente(version);
                        session.flush();
                        assertTrue(pdi.getLockVersion() > oldLockVersion);
                        session.clear();

                        PDI loaded = session.find(PDI.class, pdiId);
                        PdiVersao loadedVersion = session.find(PdiVersao.class, versionId);
                        assertNotNull(loaded);
                        assertNotNull(loadedVersion);
                        assertEquals(usuarioId, loaded.getUsuario().getId());
                        assertEquals(versionId, loaded.getVersaoVigenteId());
                        assertEquals(versionId, loaded.getVersaoVigente().getId());
                        assertEquals(pdiId, loadedVersion.getPdi().getId());
                        assertEquals("IT PDI " + suffix, loadedVersion.getTitulo());
                        assertEquals("Verificar persistência isolada", loadedVersion.getObjetivo());
                        assertEquals(1, loadedVersion.getNumero());
                        assertEquals(LocalDate.of(2026, 1, 1), loadedVersion.getInicioPlanejado());
                        assertEquals(LocalDate.of(2026, 12, 31), loadedVersion.getFimPlanejado());
                        assertEquals(StatusExecucaoPdi.NAO_INICIADO, loaded.getStatusExecucao());
                        assertEquals(StatusAprovacaoPdi.RASCUNHO, loadedVersion.getStatusAprovacao());
                        assertNotNull(loaded.getCriadoEm());
                        assertNotNull(loadedVersion.getCriadaEm());
                    } finally {
                        // There is intentionally no commit path.
                        transaction.rollback();
                    }
                } finally {
                    // Also cover beginTransaction/session failures and exceptional cleanup.
                    connection.rollback();
                }
                connection.setReadOnly(true);
                try {
                    assertAbsent(connection, "cargo", cargoId);
                    assertAbsent(connection, "equipe", equipeId);
                    assertAbsent(connection, "usuario", usuarioId);
                    assertAbsent(connection, "pdi", pdiId);
                    assertAbsent(connection, "pdi_versao", versionId);
                } finally {
                    connection.rollback();
                }
            }
        } finally {
            if (registry != null) StandardServiceRegistryBuilder.destroy(registry);
        }
    }

    private static void assertAbsent(Connection connection, String table, Object id) throws SQLException {
        if (id == null) return;
        // Table names are fixed internal literals, never configuration or request input.
        try (PreparedStatement statement = connection.prepareStatement("SELECT EXISTS (SELECT 1 FROM public." + table + " WHERE id = ?)")) {
            statement.setObject(1, id);
            try (ResultSet rows = statement.executeQuery()) {
                assertTrue(rows.next());
                assertFalse(rows.getBoolean(1), "Fixture remained after rollback");
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
        // Run only after V4 has been explicitly authorized, applied and confirmed on Neon.
        Map<String, String> scripts = Map.of(
                "1", "V1__create_core_tables.sql",
                "2", "V2__create_pdi_initial_flow.sql",
                "3", "V3__create_usuario_credencial_local.sql",
                "4", "V4__add_acao_pdi_versao_removida_em.sql");
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
        try (var stream = PdiPersistenceIT.class.getResourceAsStream("/db/migration/" + script)) {
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
        @Override public String toString() { return "PDI integration configuration [redacted]"; }
    }

    private static final class NoConnections implements ConnectionProvider {
        @Override public Connection getConnection() throws SQLException {
            throw new SQLException("Additional connections are forbidden");
        }
        @Override public void closeConnection(Connection connection) throws SQLException {
            throw new SQLException("Hibernate must not close the externally supplied connection");
        }
        @Override public boolean supportsAggressiveRelease() { return false; }
        @Override public boolean isUnwrappableAs(Class<?> type) { return type.isInstance(this); }
        @Override public <T> T unwrap(Class<T> type) {
            if (type.isInstance(this)) return type.cast(this);
            throw new IllegalArgumentException("Unsupported unwrap");
        }
    }
}
