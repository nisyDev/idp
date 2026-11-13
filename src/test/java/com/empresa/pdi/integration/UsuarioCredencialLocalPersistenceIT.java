package com.empresa.pdi.integration;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import com.empresa.pdi.enums.NivelCargo;
import com.empresa.pdi.enums.PerfilUsuario;
import com.empresa.pdi.model.Cargo;
import com.empresa.pdi.model.Equipe;
import com.empresa.pdi.model.Usuario;
import com.empresa.pdi.model.UsuarioCredencialLocal;
import com.empresa.pdi.repository.UsuarioCredencialLocalRepository;
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
import org.postgresql.util.PSQLException;
import org.slf4j.LoggerFactory;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.DefaultTransactionDefinition;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.zip.CRC32;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Explicit-only V3 persistence checks. No Spring Boot, migrations or commits.
 * Current SELECT-only credential privileges must fail the read-only preflight.
 * Each test owns a separate connection and always rolls back its fixtures.
 */
@EnabledIfEnvironmentVariable(named = "TALENTSYNK_CREDENCIAL_LOCAL_PERSISTENCE_ENABLED", matches = "true")
class UsuarioCredencialLocalPersistenceIT {
    private static final String FLAG = "TALENTSYNK_CREDENCIAL_LOCAL_PERSISTENCE_ENABLED";
    private static final String PREFIX = "TALENTSYNK_CREDENCIAL_LOCAL_IT_";
    private static final String APPROVED_BRANCH = "test-pdi-v2";
    private static final Set<String> CREDENTIAL_COLUMNS =
            Set.of("usuario_id", "senha_hash", "criada_em", "atualizada_em");
    private static final Set<String> CONSTRAINTS = Set.of(
            "pk_usuario_credencial_local", "fk_usuario_credencial_local_usuario",
            "ck_usuario_credencial_local_hash_tamanho");

    @Test
    void persistsSharedIdAndUpdatesHashAndAuditFields() {
        executeControlledScenario(Scenario.JPA_ROUND_TRIP);
    }

    @Test
    void rejectsDuplicateCredentialForSameUser() {
        executeControlledScenario(Scenario.DUPLICATE_PK);
    }

    @Test
    void rejectsCredentialForMissingUser() {
        executeControlledScenario(Scenario.MISSING_USER);
    }

    @Test
    void rejectsNullHash() {
        executeControlledScenario(Scenario.NULL_HASH);
    }

    @Test
    void rejectsHashWithInvalidLength() {
        executeControlledScenario(Scenario.INVALID_HASH_LENGTH);
    }

    private void executeControlledScenario(Scenario scenario) {
        assumeTrue("true".equals(System.getenv(FLAG)), "Credential persistence requires explicit enablement.");
        Logger hibernate = (Logger) LoggerFactory.getLogger("org.hibernate");
        Level previousHibernate = hibernate.getLevel();
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
            properties.setProperty("sslfactory", "org.postgresql.ssl.DefaultJavaSSLFactory");
            properties.setProperty("connectTimeout", "15");
            properties.setProperty("socketTimeout", "30");
            properties.setProperty("readOnlyMode", "transaction");
            properties.setProperty("options", "-c search_path=public -c statement_timeout=15000 -c lock_timeout=5000");
            stage = "read-only database identity";
            try (Connection connection = DriverManager.getConnection(config.url(), properties)) {
                connection.setReadOnly(true);
                connection.setAutoCommit(false);
                try {
                    verifyDatabase(connection, config);
                    stage = "read-only effective privileges";
                    verifyPrivileges(connection);
                    stage = "read-only Flyway history";
                    verifyMigrations(connection);
                    stage = "read-only V3 structure";
                    verifyCredentialStructure(connection);
                } finally {
                    connection.rollback();
                }
                stage = "Neon identity recheck";
                verifyNeonIdentity(config);
                stage = "persistence and mandatory rollback";
                runScenario(connection, scenario);
            }
        } catch (Exception | AssertionError failure) {
            // Never attach the original cause: JDBC details may include the hash or connection secrets.
            throw new AssertionError("Credential persistence failed; scenario=" + scenario.name()
                    + "; stage=" + stage + "; " + sanitizedDiagnostic(failure));
        } finally {
            hibernate.setLevel(previousHibernate);
            jdbc.setLevel(previousJdbc);
        }
    }

    private static void runScenario(Connection connection, Scenario scenario) throws Exception {
        StandardServiceRegistry registry = null;
        try {
            var provider = new SingleConnectionProvider(connection);
            var builder = new StandardServiceRegistryBuilder();
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
                    .applySetting("hibernate.cache.use_second_level_cache", false)
                    .applySetting("hibernate.cache.use_query_cache", false);
            registry = builder.build();
            Set<Class<?>> expected = Set.of(UsuarioCredencialLocal.class, Usuario.class, Cargo.class, Equipe.class);
            var sources = new MetadataSources(registry);
            expected.forEach(sources::addAnnotatedClass);
            var metadata = sources.buildMetadata();
            require(expected.equals(metadata.getEntityBindings().stream()
                    .map(binding -> binding.getMappedClass()).collect(Collectors.toSet())), "bootstrap.entities");
            try (SessionFactory factory = metadata.buildSessionFactory()) {
                var manager = new JpaTransactionManager(factory);
                EntityManager em = SharedEntityManagerCreator.createSharedEntityManager(factory);
                var repository = new JpaRepositoryFactory(em).getRepository(UsuarioCredencialLocalRepository.class);
                var fixtures = new Fixtures();
                TransactionStatus outer = null;
                Throwable failure = null;
                try {
                    connection.setReadOnly(false);
                    outer = manager.getTransaction(new DefaultTransactionDefinition());
                    createFixtures(em, fixtures);
                    if (scenario == Scenario.JPA_ROUND_TRIP) {
                        verifyJpaRoundTrip(em, repository, fixtures);
                    } else {
                        verifyConstraint(provider.guarded, outer, scenario, fixtures);
                    }
                } catch (Exception | AssertionError exception) {
                    failure = exception;
                } finally {
                    if (outer != null && !outer.isCompleted()) {
                        try {
                            manager.rollback(outer);
                        } catch (Exception | AssertionError exception) {
                            failure = collect(failure, exception);
                        }
                    }
                    try {
                        connection.rollback();
                    } catch (Exception | AssertionError exception) {
                        failure = collect(failure, exception);
                    }
                }
                // Also attempt these checks after an unexpected failure, only after rollback.
                try {
                    connection.setReadOnly(true);
                    verifyFixturesAbsent(connection, fixtures);
                } catch (Exception | AssertionError exception) {
                    failure = collect(failure, exception);
                } finally {
                    try {
                        connection.rollback();
                    } catch (Exception | AssertionError exception) {
                        failure = collect(failure, exception);
                    }
                }
                if (failure instanceof Exception exception) throw exception;
                if (failure instanceof AssertionError assertion) throw assertion;
            } finally {
                connection.rollback();
            }
        } finally {
            if (registry != null) StandardServiceRegistryBuilder.destroy(registry);
        }
    }

    private static void createFixtures(EntityManager em, Fixtures fixtures) {
        String suffix = UUID.randomUUID().toString();
        fixtures.cargo = new Cargo();
        fixtures.cargo.setNome("IT credential cargo " + suffix);
        fixtures.cargo.setNivel(NivelCargo.NAO_APLICAVEL);
        em.persist(fixtures.cargo);
        fixtures.equipe = new Equipe();
        fixtures.equipe.setNome("IT credential equipe " + suffix);
        em.persist(fixtures.equipe);
        fixtures.usuario = new Usuario();
        fixtures.usuario.setNome("IT credential user " + suffix);
        fixtures.usuario.setEmail("credential-it-" + suffix + "@example.invalid");
        fixtures.usuario.setPerfilAcesso(PerfilUsuario.COLABORADOR);
        fixtures.usuario.setCargo(fixtures.cargo);
        fixtures.usuario.setEquipeAtual(fixtures.equipe);
        em.persist(fixtures.usuario);
        em.flush();
        require(fixtures.usuario.getId() != null, "fixture.userId");
        fixtures.credentialIds.add(fixtures.usuario.getId());
    }

    private static void verifyJpaRoundTrip(EntityManager em, UsuarioCredencialLocalRepository repository,
                                           Fixtures fixtures) {
        UUID userId = fixtures.usuario.getId();
        require(repository.findById(userId).isEmpty(), "credential.initiallyAbsent");
        var encoder = new BCryptPasswordEncoder(12);
        String firstHash = encoder.encode(UUID.randomUUID().toString());
        var credential = new UsuarioCredencialLocal(fixtures.usuario, firstHash);
        repository.saveAndFlush(credential);
        em.clear();
        var loaded = repository.findById(userId)
                .orElseThrow(() -> new VerificationFailure("credential.persisted"));
        require(userId.equals(loaded.getUsuarioId()), "credential.sharedUuid");
        require(userId.equals(loaded.getUsuario().getId()), "credential.userRelationship");
        require(fixtures.usuario.getEmail().equals(loaded.getUsuario().getEmail()), "credential.userContent");
        require(firstHash.equals(loaded.getSenhaHash()), "credential.hashPreserved");
        require(loaded.getCriadaEm() != null && loaded.getAtualizadaEm() != null, "credential.auditPopulated");
        Instant created = loaded.getCriadaEm();
        Instant previousUpdate = loaded.getAtualizadaEm();
        // Generate the replacement after reloading: no sleeps or artificial SQL audit values.
        // A backward JVM clock must fail the assertion instead of concealing an audit problem.
        String replacementHash = encoder.encode(UUID.randomUUID().toString());
        loaded.setSenhaHash(replacementHash);
        repository.saveAndFlush(loaded);
        em.clear();
        var updated = repository.findById(userId)
                .orElseThrow(() -> new VerificationFailure("credential.updatedRow"));
        require(userId.equals(updated.getUsuarioId()) && userId.equals(updated.getUsuario().getId()),
                "credential.relationshipPreserved");
        require(replacementHash.equals(updated.getSenhaHash()) && !firstHash.equals(updated.getSenhaHash()),
                "credential.hashUpdated");
        require(created.equals(updated.getCriadaEm()), "credential.creationTimePreserved");
        require(updated.getAtualizadaEm() != null && updated.getAtualizadaEm().isAfter(previousUpdate),
                "credential.updateTimeAdvanced");
    }

    private static void verifyConstraint(Connection connection, TransactionStatus outer, Scenario scenario,
                                          Fixtures fixtures) throws SQLException {
        UUID userId = fixtures.usuario.getId();
        String hash = new BCryptPasswordEncoder(12).encode(UUID.randomUUID().toString());
        if (scenario == Scenario.DUPLICATE_PK) {
            require(insertCredential(connection, userId, hash) == 1, "constraint.duplicateBaselineInserted");
        } else if (scenario == Scenario.MISSING_USER) {
            userId = UUID.randomUUID();
            require(!exists(connection, "usuario", "id", userId), "constraint.orphanUserAbsent");
            fixtures.credentialIds.add(userId);
        } else if (scenario == Scenario.NULL_HASH) {
            hash = null;
        } else if (scenario == Scenario.INVALID_HASH_LENGTH) {
            // 61 characters could fail VARCHAR before the CHECK. 59 reaches the actual CHECK.
            hash = hash.substring(0, 59);
        }
        SQLException rejection = null;
        try {
            insertCredential(connection, userId, hash);
        } catch (SQLException exception) {
            rejection = exception;
            outer.setRollbackOnly();
        }
        if (rejection == null) throw new VerificationFailure("constraint.expectedRejection");
        PSQLException postgres = findPostgres(rejection);
        if (postgres == null || !scenario.sqlState.equals(postgres.getSQLState())) {
            throw new VerificationFailure("constraint.sqlState", rejection);
        }
        var server = postgres.getServerErrorMessage();
        if (server == null || !"public".equals(server.getSchema())
                || !"usuario_credencial_local".equals(server.getTable())) {
            throw new VerificationFailure("constraint.target", rejection);
        }
        if (scenario == Scenario.NULL_HASH) {
            if (!"senha_hash".equals(server.getColumn())) {
                throw new VerificationFailure("constraint.notNullColumn", rejection);
            }
        } else if (!scenario.constraint.equals(server.getConstraint())) {
            throw new VerificationFailure("constraint.name", rejection);
        }
        // No further SQL in this aborted transaction. The external finally rolls it back.
    }

    private static int insertCredential(Connection connection, UUID userId, String hash) throws SQLException {
        // Omitting timestamps also exercises their database defaults on the valid baseline INSERT.
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO public.usuario_credencial_local (usuario_id, senha_hash) VALUES (?, ?)")) {
            statement.setObject(1, userId);
            if (hash == null) statement.setNull(2, Types.VARCHAR);
            else statement.setString(2, hash);
            return statement.executeUpdate();
        }
    }

    private static void verifyFixturesAbsent(Connection connection, Fixtures fixtures) throws SQLException {
        Set<UUID> credentials = new LinkedHashSet<>(fixtures.credentialIds);
        if (fixtures.usuario != null && fixtures.usuario.getId() != null) credentials.add(fixtures.usuario.getId());
        for (UUID id : credentials) {
            require(!exists(connection, "usuario_credencial_local", "usuario_id", id), "rollback.credentialAbsent");
        }
        if (fixtures.usuario != null && fixtures.usuario.getId() != null) {
            require(!exists(connection, "usuario", "id", fixtures.usuario.getId()), "rollback.userAbsent");
        }
        if (fixtures.equipe != null && fixtures.equipe.getId() != null) {
            require(!exists(connection, "equipe", "id", fixtures.equipe.getId()), "rollback.teamAbsent");
        }
        if (fixtures.cargo != null && fixtures.cargo.getId() != null) {
            require(!exists(connection, "cargo", "id", fixtures.cargo.getId()), "rollback.jobAbsent");
        }
    }

    private static boolean exists(Connection connection, String table, String column, UUID id) throws SQLException {
        // All identifiers are fixed internal literals, never environment or request input.
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT EXISTS (SELECT 1 FROM public." + table + " WHERE " + column + " = ?)")) {
            statement.setObject(1, id);
            try (ResultSet rows = statement.executeQuery()) {
                require(rows.next(), "fixture.existenceResult");
                return rows.getBoolean(1);
            }
        }
    }

    private static void verifyPrivileges(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(
                "SELECT pg_catalog.has_database_privilege(current_user, current_database(), 'CONNECT'), "
                        + "pg_catalog.has_schema_privilege(current_user, 'public', 'USAGE')")) {
            require(rows.next() && rows.getBoolean(1) && rows.getBoolean(2), "privilege.databaseAndSchema");
        }
        List<String> missing = new ArrayList<>();
        for (String privilege : List.of("SELECT", "INSERT")) {
            checkColumns(connection, "usuario_credencial_local", privilege, CREDENTIAL_COLUMNS, missing);
            checkColumns(connection, "cargo", privilege, Set.of("id", "nome", "descricao", "nivel", "ativo"), missing);
            checkColumns(connection, "equipe", privilege, Set.of("id", "nome", "lider_id", "descricao", "ativo"), missing);
            checkColumns(connection, "usuario", privilege, Set.of("id", "ad_object_id", "nome", "email", "cargo_id",
                    "gestor_id", "perfil_acesso", "ativo", "data_criacao", "data_atualizacao", "equipe_atual_id"), missing);
        }
        checkColumns(connection, "usuario_credencial_local", "UPDATE", Set.of("senha_hash", "atualizada_em"), missing);
        checkColumns(connection, "flyway_schema_history", "SELECT",
                Set.of("version", "script", "checksum", "success", "installed_rank"), missing);
        // This check is reached in READ ONLY mode, before fixtures, hash generation or Hibernate writes.
        require(missing.isEmpty(), "missing_privileges[" + String.join(",", missing) + "]");
    }

    private static void checkColumns(Connection connection, String table, String privilege, Set<String> columns,
                                      List<String> missing) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT pg_catalog.has_column_privilege(current_user, ?, ?, ?)")) {
            for (String column : columns.stream().sorted().toList()) {
                statement.setString(1, "public." + table);
                statement.setString(2, column);
                statement.setString(3, privilege);
                try (ResultSet rows = statement.executeQuery()) {
                    require(rows.next(), "privilege.result");
                    if (!rows.getBoolean(1)) missing.add(table + "." + privilege + "." + column);
                }
            }
        }
    }

    private static void verifyCredentialStructure(Connection connection) throws SQLException {
        Map<String, String> types = Map.of("usuario_id", "uuid", "senha_hash", "varchar",
                "criada_em", "timestamptz", "atualizada_em", "timestamptz");
        Set<String> found = new LinkedHashSet<>();
        try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery("""
                SELECT a.attname, t.typname, a.atttypmod, a.attnotnull, a.attidentity,
                       pg_catalog.pg_get_expr(d.adbin, d.adrelid)
                FROM pg_catalog.pg_attribute a
                JOIN pg_catalog.pg_type t ON t.oid = a.atttypid
                LEFT JOIN pg_catalog.pg_attrdef d ON d.adrelid = a.attrelid AND d.adnum = a.attnum
                WHERE a.attrelid = 'public.usuario_credencial_local'::regclass
                  AND a.attnum > 0 AND NOT a.attisdropped
                ORDER BY a.attnum
                """)) {
            while (rows.next()) {
                String column = rows.getString(1);
                require(types.containsKey(column) && found.add(column), "schema.columns");
                require(types.get(column).equals(rows.getString(2)), "schema.columnType");
                require(rows.getBoolean(4) && "".equals(rows.getString(5)), "schema.requiredWithoutIdentity");
                if ("senha_hash".equals(column)) require(rows.getInt(3) == 64, "schema.hashLength60");
                String defaultExpression = rows.getString(6);
                if (column.endsWith("_em")) {
                    require(defaultExpression != null && "CURRENT_TIMESTAMP".equalsIgnoreCase(defaultExpression.trim()),
                            "schema.timestampDefault");
                } else require(defaultExpression == null, "schema.noUnexpectedDefault");
            }
        }
        require(found.equals(CREDENTIAL_COLUMNS), "schema.exactColumns");
        Set<String> constraints = new LinkedHashSet<>();
        try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery("""
                SELECT c.conname, c.contype::text, c.convalidated,
                       (SELECT string_agg(a.attname, ',' ORDER BY k.ord)
                        FROM unnest(c.conkey) WITH ORDINALITY k(attnum, ord)
                        JOIN pg_catalog.pg_attribute a ON a.attrelid = c.conrelid AND a.attnum = k.attnum),
                       n.nspname, r.relname,
                       (SELECT string_agg(a.attname, ',' ORDER BY k.ord)
                        FROM unnest(c.confkey) WITH ORDINALITY k(attnum, ord)
                        JOIN pg_catalog.pg_attribute a ON a.attrelid = c.confrelid AND a.attnum = k.attnum),
                       c.confupdtype::text, c.confdeltype::text, c.condeferrable,
                       pg_catalog.pg_get_constraintdef(c.oid),
                       EXISTS (SELECT 1 FROM pg_catalog.pg_index i WHERE i.indexrelid = c.conindid
                               AND i.indisprimary AND i.indisunique AND i.indisvalid AND i.indisready)
                FROM pg_catalog.pg_constraint c
                LEFT JOIN pg_catalog.pg_class r ON r.oid = c.confrelid
                LEFT JOIN pg_catalog.pg_namespace n ON n.oid = r.relnamespace
                WHERE c.conrelid = 'public.usuario_credencial_local'::regclass AND c.contype IN ('p', 'f', 'c')
                """)) {
            while (rows.next()) {
                String name = rows.getString(1);
                require(CONSTRAINTS.contains(name) && constraints.add(name) && rows.getBoolean(3), "schema.constraints");
                require(!rows.getBoolean(10), "schema.notDeferrable");
                switch (name) {
                    case "pk_usuario_credencial_local" -> require("p".equals(rows.getString(2))
                            && "usuario_id".equals(rows.getString(4)) && rows.getBoolean(12), "schema.primaryKeyIndex");
                    case "fk_usuario_credencial_local_usuario" -> require("f".equals(rows.getString(2))
                            && "usuario_id".equals(rows.getString(4)) && "public".equals(rows.getString(5))
                            && "usuario".equals(rows.getString(6)) && "id".equals(rows.getString(7))
                            && "a".equals(rows.getString(8)) && "a".equals(rows.getString(9)), "schema.foreignKey");
                    case "ck_usuario_credencial_local_hash_tamanho" -> {
                        String expression = rows.getString(11).toLowerCase(Locale.ROOT)
                                .replaceAll("\\s+", "").replace("::text", "").replace("(", "").replace(")", "");
                        require("c".equals(rows.getString(2)) && "senha_hash".equals(rows.getString(4))
                                && "checkchar_lengthsenha_hash=60".equals(expression), "schema.hashCheck");
                    }
                    default -> throw new VerificationFailure("schema.unexpectedConstraint");
                }
            }
        }
        require(constraints.equals(CONSTRAINTS), "schema.exactConstraints");
    }

    private static void verifyDatabase(Connection connection, Config config) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(
                "SELECT current_database(), current_schema(), current_user, session_user, current_setting('transaction_read_only')")) {
            require(rows.next() && config.database().equals(rows.getString(1)) && "public".equals(rows.getString(2))
                    && config.user().equals(rows.getString(3)) && config.user().equals(rows.getString(4))
                    && "on".equals(rows.getString(5)), "preflight.databaseIdentityAndReadOnly");
        }
    }

    private static void verifyMigrations(Connection connection) throws Exception {
        Map<String, String> scripts = Map.of("1", "V1__create_core_tables.sql",
                "2", "V2__create_pdi_initial_flow.sql", "3", "V3__create_usuario_credencial_local.sql");
        Set<String> found = new LinkedHashSet<>();
        try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(
                "SELECT version, script, checksum, success FROM public.flyway_schema_history ORDER BY installed_rank")) {
            while (rows.next()) {
                String version = rows.getString(1);
                require(scripts.containsKey(version) && found.add(version), "flyway.exactVersionsWithoutDuplicates");
                require(scripts.get(version).equals(rows.getString(2)), "flyway.script");
                int checksum = rows.getInt(3);
                require(!rows.wasNull() && checksum == localChecksum(scripts.get(version)), "flyway.checksum");
                if ("1".equals(version)) require(checksum == 467754627, "flyway.coreChecksum");
                require(rows.getBoolean(4), "flyway.success");
            }
        }
        require(found.equals(scripts.keySet()), "flyway.completeHistory");
    }

    private static int localChecksum(String script) throws Exception {
        CRC32 crc = new CRC32();
        try (var stream = UsuarioCredencialLocalPersistenceIT.class.getResourceAsStream("/db/migration/" + script)) {
            require(stream != null, "flyway.resource");
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

    private static Config configuration() {
        String host = required("HOST");
        String database = required("DATABASE");
        String user = required("USER");
        require("pdi_db".equals(database), "config.database");
        require("talentsynk_test_runner".equals(user), "config.user");
        require("public".equals(required("SCHEMA")), "config.schema");
        require(host.matches("[a-z0-9.-]+\\.neon\\.tech") && !host.contains("-pooler"), "config.directHost");
        return new Config(host, database, user, required("PASSWORD"), identifier("PROJECT_ID"),
                identifier("BRANCH_ID"), identifier("ENDPOINT_ID"), required("NEON_API_KEY"));
    }

    private static String required(String name) {
        String value = System.getenv(PREFIX + name);
        require(value != null && !value.isBlank(), "config.missing." + PREFIX + name);
        return value;
    }

    private static String identifier(String name) {
        String value = required(name);
        require(value.matches("[a-z0-9-]{1,100}"), "config.resourceIdentifier");
        return value;
    }

    private static void verifyNeonIdentity(Config config) throws Exception {
        try (HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NEVER).build()) {
            JsonNode branch = api(client, config, "/branches/" + config.branchId()).path("branch");
            require(config.branchId().equals(branch.path("id").asText())
                    && config.projectId().equals(branch.path("project_id").asText())
                    && APPROVED_BRANCH.equals(branch.path("name").asText())
                    && branch.path("default").isBoolean() && !branch.path("default").asBoolean(), "neon.branchIdentity");
            JsonNode endpoint = api(client, config, "/endpoints/" + config.endpointId()).path("endpoint");
            require(config.endpointId().equals(endpoint.path("id").asText())
                    && config.branchId().equals(endpoint.path("branch_id").asText())
                    && config.projectId().equals(endpoint.path("project_id").asText())
                    && config.host().equals(endpoint.path("host").asText())
                    && "read_write".equals(endpoint.path("type").asText()), "neon.endpointIdentity");
        }
    }

    private static JsonNode api(HttpClient client, Config config, String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(
                        "https://console.neon.tech/api/v2/projects/" + config.projectId() + path))
                .timeout(Duration.ofSeconds(20)).header("Authorization", "Bearer " + config.apiKey())
                .header("Accept", "application/json").GET().build();
        var response = client.send(request, HttpResponse.BodyHandlers.ofString());
        require(response.statusCode() == 200, "neon.apiUnavailable");
        return new ObjectMapper().readTree(response.body());
    }

    private static void require(boolean condition, String check) {
        if (!condition) throw new VerificationFailure(check);
    }

    private static Throwable collect(Throwable failure, Throwable cleanupFailure) {
        if (failure == null) return cleanupFailure;
        failure.addSuppressed(cleanupFailure);
        return failure;
    }

    private static PSQLException findPostgres(Throwable failure) {
        for (Throwable current : causeGraph(failure)) if (current instanceof PSQLException postgres) return postgres;
        return null;
    }

    private static List<Throwable> causeGraph(Throwable failure) {
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        var queue = new ArrayDeque<Throwable>();
        var result = new ArrayList<Throwable>();
        queue.add(failure);
        while (!queue.isEmpty() && result.size() < 32) {
            Throwable current = queue.removeFirst();
            if (!seen.add(current)) continue;
            result.add(current);
            if (current.getCause() != null) queue.addLast(current.getCause());
            Collections.addAll(queue, current.getSuppressed());
            if (current instanceof SQLException sql && sql.getNextException() != null) queue.addLast(sql.getNextException());
        }
        return result;
    }

    private static String sanitizedDiagnostic(Throwable failure) {
        String check = "UNAVAILABLE";
        for (Throwable current : causeGraph(failure)) {
            if (current instanceof VerificationFailure verification) {
                check = verification.check;
                break;
            }
        }
        PSQLException postgres = findPostgres(failure);
        String state = postgres == null ? null : postgres.getSQLState();
        var server = postgres == null ? null : postgres.getServerErrorMessage();
        return "category=" + failure.getClass().getSimpleName() + "; check=" + check
                + "; sqlState=" + (state != null && state.matches("[A-Z0-9]{5}") ? state : "UNAVAILABLE")
                + "; schema=" + safeIdentifier(server == null ? null : server.getSchema(), Set.of("public"))
                + "; table=" + safeIdentifier(server == null ? null : server.getTable(),
                        Set.of("usuario_credencial_local", "usuario", "cargo", "equipe", "flyway_schema_history"))
                + "; column=" + safeIdentifier(server == null ? null : server.getColumn(), CREDENTIAL_COLUMNS)
                + "; constraint=" + safeIdentifier(server == null ? null : server.getConstraint(), CONSTRAINTS);
    }

    private static String safeIdentifier(String value, Set<String> allowed) {
        if (value == null) return "UNAVAILABLE";
        return allowed.contains(value) ? value : "REDACTED";
    }

    private enum Scenario {
        JPA_ROUND_TRIP(null, null),
        DUPLICATE_PK("23505", "pk_usuario_credencial_local"),
        MISSING_USER("23503", "fk_usuario_credencial_local_usuario"),
        NULL_HASH("23502", null),
        INVALID_HASH_LENGTH("23514", "ck_usuario_credencial_local_hash_tamanho");

        private final String sqlState;
        private final String constraint;

        Scenario(String sqlState, String constraint) {
            this.sqlState = sqlState;
            this.constraint = constraint;
        }
    }

    private static final class Fixtures {
        private Cargo cargo;
        private Equipe equipe;
        private Usuario usuario;
        private final Set<UUID> credentialIds = new LinkedHashSet<>();
    }

    private static final class VerificationFailure extends AssertionError {
        private final String check;

        VerificationFailure(String check) {
            super("Verification failed: " + check);
            this.check = check;
        }

        VerificationFailure(String check, Throwable cause) {
            this(check);
            initCause(cause);
        }
    }

    private record Config(String host, String database, String user, String password,
                          String projectId, String branchId, String endpointId, String apiKey) {
        String url() { return "jdbc:postgresql://" + host + ":5432/" + database; }
        @Override public String toString() { return "Credential integration configuration [redacted]"; }
    }

    private static final class SingleConnectionProvider implements ConnectionProvider {
        private final Connection guarded;

        SingleConnectionProvider(Connection connection) {
            guarded = (Connection) java.lang.reflect.Proxy.newProxyInstance(
                    UsuarioCredencialLocalPersistenceIT.class.getClassLoader(), new Class<?>[]{Connection.class},
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
                        } catch (java.lang.reflect.InvocationTargetException exception) {
                            throw exception.getCause();
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
}
