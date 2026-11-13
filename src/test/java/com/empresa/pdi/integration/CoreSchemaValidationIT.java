package com.empresa.pdi.integration;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import com.empresa.pdi.model.Cargo;
import com.empresa.pdi.model.Equipe;
import com.empresa.pdi.model.HistoricoEquipe;
import com.empresa.pdi.model.Usuario;
import org.hibernate.SessionFactory;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistry;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.hibernate.engine.config.spi.ConfigurationService;
import org.hibernate.tool.schema.Action;
import org.hibernate.tool.schema.spi.SchemaManagementException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/** Metadata-only integration test; explicit opt-in, no Spring/Flyway bootstrap. */
@EnabledIfSystemProperty(named = "talentsynk.coreSchemaValidation", matches = "true")
class CoreSchemaValidationIT {
    // User-approved SHA-256 of the direct test-core-validation hostname.
    // Compared before any connection; never replace from environment.
    private static final String ENDPOINT_APROVADO_SHA256 = "14b4eb5b76c1dec2fbc170c827f691b2a9dd1c3c9f2fb8fe3a0da5193b75eb28";
    private static final String ROLE_APROVADA = "core_schema_validator";
    private static final Set<Class<?>> CORE = Set.of(Usuario.class, Cargo.class, Equipe.class, HistoricoEquipe.class);
    private static final Map<String, Object> ACOES = Map.of(
            "hibernate.hbm2ddl.auto", "validate",
            "jakarta.persistence.schema-generation.database.action", Action.VALIDATE,
            "jakarta.persistence.schema-generation.scripts.action", Action.NONE);
    // Privilege inquiry functions include inherited grants and PUBLIC grants.
    // SET-reachable roles are evaluated too, without actually executing SET ROLE.
    private static final String ROLES_ACESSIVEIS = """
            WITH roles_acessiveis AS (
                SELECT oid, rolsuper, rolcreatedb, rolcreaterole, rolreplication, rolbypassrls
                FROM pg_catalog.pg_roles
                WHERE rolname = current_user OR pg_catalog.pg_has_role(oid, 'SET')
            )
            """;

    @Test
    void validaSomenteCoreNoSchemaPublicComContaRestrita() {
        org.junit.jupiter.api.Assumptions.assumeTrue(
                "true".equals(System.getProperty("talentsynk.coreSchemaValidation")),
                "CORE schema validation requires explicit enablement."
        );
        Logger hibernateLog = (Logger) LoggerFactory.getLogger("org.hibernate");
        Logger sqlLog = (Logger) LoggerFactory.getLogger("org.hibernate.SQL");
        Level hibernateLevel = hibernateLog.getLevel();
        Level sqlLevel = sqlLog.getLevel();
        java.util.logging.Logger jdbcLog = java.util.logging.Logger.getLogger("org.postgresql");
        java.util.logging.Level jdbcLevel = jdbcLog.getLevel();
        String etapa = "configuração local";
        StandardServiceRegistry registry = null;
        AssertionError falha = null;
        try {
            hibernateLog.setLevel(Level.OFF);
            sqlLog.setLevel(Level.OFF);
            jdbcLog.setLevel(java.util.logging.Level.OFF);
            verificarConfiguracaoExterna();
            exigir("true".equals(variavel("CORE_SCHEMA_TEST_SECURITY_CONTRACT_APPROVED")),
                    "O contrato de segurança específico do teste CORE precisa de aprovação administrativa explícita");
            DataSource dataSource = criarDataSource();

            // Inspect and discard all inherited Hibernate settings before creating services.
            StandardServiceRegistryBuilder builder = new StandardServiceRegistryBuilder();
            rejeitarGeracaoExterna(builder.getSettings());
            builder.getSettings().clear();
            builder.applySettings(ACOES)
                    .applySetting("hibernate.connection.datasource", dataSource)
                    .applySetting("hibernate.default_schema", "public")
                    .applySetting("hibernate.show_sql", false)
                    .applySetting("hibernate.format_sql", false)
                    .applySetting("hibernate.use_sql_comments", false)
                    .applySetting("hibernate.cache.use_second_level_cache", false)
                    .applySetting("hibernate.cache.use_query_cache", false);
            verificarAcoesEfetivas(builder.getSettings());

            etapa = "conexão e verificação de privilégios";
            try (Connection ignored = dataSource.getConnection()) {
                // Each connection is checked before Hibernate can use it.
            }
            etapa = "bootstrap restrito do Hibernate";
            registry = builder.build();
            verificarAcoesEfetivas(registry.getService(ConfigurationService.class).getSettings());
            etapa = "validação dos metadados CORE";
            MetadataSources sources = new MetadataSources(registry)
                    .addAnnotatedClass(Usuario.class)
                    .addAnnotatedClass(Cargo.class)
                    .addAnnotatedClass(Equipe.class)
                    .addAnnotatedClass(HistoricoEquipe.class);
            try (SessionFactory factory = sources.buildMetadata().buildSessionFactory()) {
                Set<Class<?>> entidades = new HashSet<>();
                factory.getMetamodel().getEntities().forEach(entity -> entidades.add(entity.getJavaType()));
                exigir(entidades.equals(CORE), "O contexto contém entidades fora do CORE");
                // No Session/EntityManager or application data query is opened.
            }
        } catch (Exception exception) {
            falha = diagnostico(etapa, exception);
        } finally {
            try {
                if (registry != null) {
                    StandardServiceRegistryBuilder.destroy(registry);
                }
            } catch (Exception exception) {
                AssertionError encerramento = diagnostico("encerramento do contexto", exception);
                if (falha == null) {
                    falha = encerramento;
                } else {
                    falha.addSuppressed(encerramento);
                }
            } finally {
                hibernateLog.setLevel(hibernateLevel);
                sqlLog.setLevel(sqlLevel);
                jdbcLog.setLevel(jdbcLevel);
            }
        }
        if (falha != null) {
            throw falha;
        }
    }

    private static void verificarConfiguracaoExterna() throws Exception {
        rejeitarGeracaoExterna(System.getProperties());
        ClassLoader loader = CoreSchemaValidationIT.class.getClassLoader();
        exigir(!loader.getResources("hibernate.properties").hasMoreElements()
                        && !loader.getResources("hibernate.cfg.xml").hasMoreElements(),
                "Remova recursos externos de configuração Hibernate deste contexto isolado");
    }

    private static boolean chaveDeGeracao(String chave) {
        return chave.startsWith("hibernate.hbm2ddl")
                || chave.startsWith("jakarta.persistence.schema-generation")
                || chave.startsWith("javax.persistence.schema-generation")
                || chave.equals("jakarta.persistence.sql-load-script-source")
                || chave.equals("javax.persistence.sql-load-script-source")
                || chave.equals("hibernate.schema_management_tool");
    }

    private static void rejeitarGeracaoExterna(Map<?, ?> configuracao) {
        exigir(configuracao.keySet().stream().noneMatch(key -> chaveDeGeracao(String.valueOf(key))),
                "Configuração externa de geração de esquema não é permitida");
    }

    private static void verificarAcoesEfetivas(Map<?, ?> configuracao) {
        exigir(ACOES.entrySet().stream().allMatch(e -> e.getValue().equals(configuracao.get(e.getKey())))
                        && configuracao.keySet().stream().map(String::valueOf)
                        .noneMatch(key -> chaveDeGeracao(key) && !ACOES.containsKey(key)),
                "Ações efetivas de esquema diferentes da validação isolada autorizada");
    }

    private static DataSource criarDataSource() {
        String url = variavel("CORE_SCHEMA_TEST_DB_URL");
        String user = variavel("CORE_SCHEMA_TEST_DB_USER");
        String password = variavel("CORE_SCHEMA_TEST_DB_PASSWORD");
        exigir(ROLE_APROVADA.equals(user), "A conta deve ser core_schema_validator");
        exigir(ENDPOINT_APROVADO_SHA256.matches("[0-9a-f]{64}"),
                "Pendente o vínculo revisado com o endpoint direto aprovado de test-core-validation");
        exigir(url.startsWith("jdbc:postgresql://"), "Exige URL JDBC PostgreSQL explícita");
        URI uri = URI.create(url.substring("jdbc:".length()));
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        exigir(host.endsWith(".neon.tech") && sha256(host).equals(ENDPOINT_APROVADO_SHA256),
                "Endpoint diferente do endpoint direto aprovado de test-core-validation");
        exigir(!host.contains("-pooler."), "Utilize o endpoint direto, sem pooler");
        exigir("/pdi_db".equals(uri.getRawPath()), "Banco diferente de pdi_db");
        exigir(uri.getPort() == -1 || uri.getPort() == 5432, "Porta diferente da porta Neon esperada");
        exigir(uri.getRawUserInfo() == null && uri.getRawFragment() == null,
                "Não incluir identidade, senha ou fragmentos na URL");
        Map<String, String> parametros = new HashMap<>();
        exigir(uri.getRawQuery() != null, "Informe sslmode=verify-full explicitamente");
        for (String item : uri.getRawQuery().split("&", -1)) {
            String[] partes = item.split("=", -1);
            exigir(partes.length == 2 && Set.of("sslmode", "channelBinding").contains(partes[0]),
                    "Parâmetro JDBC desconhecido, codificado ou ambíguo");
            exigir(parametros.putIfAbsent(partes[0], partes[1]) == null, "Parâmetro JDBC duplicado");
        }
        exigir("verify-full".equals(parametros.get("sslmode")), "TLS com verify-full é obrigatório");
        String channelBinding = parametros.getOrDefault("channelBinding", "prefer");
        exigir(Set.of("require", "prefer", "disable").contains(channelBinding), "channelBinding inválido");
        // Never hand the original query string to the driver. TLS properties are fixed below.
        String urlCanonica = "jdbc:postgresql://" + host + ":5432/pdi_db";
        return new DataSourceRestrito(urlCanonica, user, password, channelBinding);
    }

    private static String variavel(String nome) {
        String valor = System.getenv(nome);
        exigir(valor != null && !valor.isBlank(), "Variável obrigatória indisponível: " + nome);
        return valor;
    }

    private static String sha256(String hostname) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(hostname.trim().toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new PreRequisitoException("SHA-256 indisponível na JVM");
        }
    }

    private static void exigir(boolean condicao, String mensagem) {
        if (!condicao) {
            throw new PreRequisitoException(mensagem);
        }
    }

    private static final class PreRequisitoException extends IllegalStateException {
        private PreRequisitoException(String mensagem) {
            super(mensagem);
        }
    }

    private static AssertionError diagnostico(String etapa, Exception exception) {
        StringBuilder detalhe = new StringBuilder("Falha na etapa: ").append(etapa)
                .append("; categoria: ").append(exception.getClass().getSimpleName());
        if (exception instanceof PreRequisitoException) {
            detalhe.append("; verificação: ").append(exception.getMessage());
        }
        Set<Throwable> visitados = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        for (Throwable causa = exception; causa != null && visitados.add(causa); causa = causa.getCause()) {
            if (causa instanceof SQLException sql && sql.getSQLState() != null
                    && sql.getSQLState().matches("[0-9A-Z]{5}")) {
                detalhe.append("; SQLSTATE: ").append(sql.getSQLState());
            }
            if (causa instanceof SchemaManagementException && causa.getMessage() != null) {
                String mensagem = causa.getMessage();
                if (mensagem.contains("missing table")) {
                    detalhe.append("; tabela ausente");
                } else if (mensagem.contains("missing column")) {
                    detalhe.append("; coluna ausente");
                } else if (mensagem.contains("wrong column type")) {
                    detalhe.append("; tipo de coluna incompatível");
                }
                for (String tabela : Set.of("usuario", "cargo", "equipe", "historico_equipe")) {
                    if (mensagem.contains("public." + tabela)) {
                        detalhe.append("; referência CORE: ").append(tabela);
                    }
                }
            }
        }
        // Only fixed diagnostics and SQLSTATE, never raw driver/schema messages or causes.
        return new AssertionError(detalhe.toString());
    }

    private static void verificarPermissoes(Connection connection) throws SQLException {
        verificar(connection, """
                SELECT current_database() = 'pdi_db' AND current_schema() = 'public'
                   AND current_user = 'core_schema_validator'
                   AND session_user = 'core_schema_validator'
                   AND current_setting('transaction_read_only') = 'on'
                   AND current_setting('default_transaction_read_only') = 'on'
                   AND current_setting('server_version_num')::integer / 10000 = 18
                """, "Destino, versão ou modo somente leitura não confirmado");
        verificar(connection, ROLES_ACESSIVEIS + """
                SELECT NOT EXISTS (
                    SELECT 1 FROM roles_acessiveis
                    WHERE rolsuper OR rolcreatedb OR rolcreaterole OR rolreplication OR rolbypassrls)
                   AND NOT EXISTS (
                    SELECT 1 FROM pg_catalog.pg_auth_members m
                    JOIN roles_acessiveis r ON r.oid = m.member WHERE m.admin_option)
                   AND NOT EXISTS (
                    SELECT 1 FROM pg_catalog.pg_roles
                    WHERE rolname IN ('neon_superuser', 'pg_write_server_files', 'pg_execute_server_program',
                                      'pg_signal_backend', 'pg_checkpoint', 'pg_create_subscription')
                      AND (pg_catalog.pg_has_role(oid, 'USAGE') OR pg_catalog.pg_has_role(oid, 'SET')))
                """, "Conta ou role alcançável permite administração ou elevação de privilégios");
        verificar(connection, ROLES_ACESSIVEIS + """
                SELECT pg_catalog.has_database_privilege(current_database(), 'CONNECT')
                   AND pg_catalog.has_schema_privilege('public', 'USAGE')
                   AND NOT EXISTS (
                    SELECT 1 FROM roles_acessiveis r
                    WHERE pg_catalog.has_database_privilege(r.oid, current_database(), 'CREATE'))
                   AND NOT EXISTS (
                    SELECT 1 FROM pg_catalog.pg_namespace n CROSS JOIN roles_acessiveis r
                    WHERE n.nspname !~ '^pg_' AND n.nspname <> 'information_schema'
                      AND pg_catalog.has_schema_privilege(r.oid, n.oid, 'CREATE'))
                   AND NOT EXISTS (
                    SELECT 1 FROM pg_catalog.pg_shdepend d
                    JOIN pg_catalog.pg_roles proprietario ON proprietario.oid = d.refobjid
                    CROSS JOIN roles_acessiveis r
                    WHERE d.refclassid = 'pg_catalog.pg_authid'::regclass AND d.deptype = 'o'
                      AND pg_catalog.pg_has_role(r.oid, proprietario.oid, 'USAGE')
                      AND (d.dbid = (SELECT oid FROM pg_catalog.pg_database WHERE datname = current_database())
                           OR (d.dbid = 0 AND d.classid = 'pg_catalog.pg_database'::regclass
                               AND d.objid = (SELECT oid FROM pg_catalog.pg_database
                                              WHERE datname = current_database()))))
                """, "Conta ou role alcançável possui CREATE ou ownership persistente no banco atual");
        verificar(connection, ROLES_ACESSIVEIS + """
                SELECT NOT EXISTS (
                    SELECT 1 FROM pg_catalog.pg_class c
                    JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
                    CROSS JOIN roles_acessiveis r
                    WHERE n.nspname !~ '^pg_' AND n.nspname <> 'information_schema'
                      AND CASE WHEN c.relkind IN ('r', 'p', 'v', 'm', 'f') THEN
                        pg_catalog.has_table_privilege(r.oid, c.oid,
                            'INSERT,UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER,MAINTAIN')
                        OR pg_catalog.has_any_column_privilege(r.oid, c.oid, 'INSERT,UPDATE,REFERENCES')
                        WHEN c.relkind = 'S' THEN pg_catalog.has_sequence_privilege(r.oid, c.oid, 'USAGE,UPDATE')
                        ELSE false END)
                """, "Conta ou role alcançável possui escrita/manutenção em objetos persistentes de usuário");
        // TEMPORARY, legitimate read roles and SECURITY DEFINER existence are not blanket failures.
        // Residual routine/extension capabilities require explicit CORE contract approval;
        // this test must not invoke those capabilities or certify universal read-only access.
        verificar(connection, """
                SELECT count(*) = 4 AND bool_and(pg_catalog.has_table_privilege(c.oid, 'SELECT'))
                FROM pg_catalog.pg_class c
                JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
                WHERE n.nspname = 'public' AND c.relkind IN ('r', 'p')
                  AND c.relname IN ('usuario', 'cargo', 'equipe', 'historico_equipe')
                """, "As quatro tabelas CORE e suas permissões SELECT não foram confirmadas");
    }

    private static void verificar(Connection connection, String sql, String mensagem) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.setQueryTimeout(15);
            try (ResultSet resultado = statement.executeQuery(sql)) {
                exigir(resultado.next() && resultado.getBoolean(1), mensagem);
            }
        }
    }

    private record DataSourceRestrito(String url, String user, String password, String channelBinding)
            implements DataSource {
        @Override
        public Connection getConnection() throws SQLException {
            Properties propriedades = new Properties();
            propriedades.setProperty("user", user);
            propriedades.setProperty("password", password);
            propriedades.setProperty("sslmode", "verify-full");
            propriedades.setProperty("sslfactory", "org.postgresql.ssl.DefaultJavaSSLFactory");
            propriedades.setProperty("channelBinding", channelBinding);
            propriedades.setProperty("readOnly", "true");
            propriedades.setProperty("readOnlyMode", "always");
            propriedades.setProperty("connectTimeout", "15");
            propriedades.setProperty("socketTimeout", "30");
            propriedades.setProperty("options", "-c default_transaction_read_only=on -c search_path=public");
            Connection connection = DriverManager.getConnection(url, propriedades);
            try {
                connection.setReadOnly(true);
                verificarPermissoes(connection);
                return connection;
            } catch (SQLException | RuntimeException exception) {
                try {
                    connection.close();
                } catch (SQLException encerramento) {
                    exception.addSuppressed(encerramento);
                }
                throw exception;
            }
        }

        @Override public String toString() { return "DataSourceRestrito[redigido]"; }
        @Override public Connection getConnection(String user, String password) throws SQLException {
            throw new SQLFeatureNotSupportedException("Credenciais alternativas não são permitidas");
        }
        @Override public PrintWriter getLogWriter() { return null; }
        @Override public void setLogWriter(PrintWriter writer) throws SQLException {
            throw new SQLFeatureNotSupportedException("Log JDBC não é permitido neste teste");
        }
        @Override public void setLoginTimeout(int seconds) throws SQLException {
            throw new SQLFeatureNotSupportedException("Timeout é fixado internamente");
        }
        @Override public int getLoginTimeout() { return 15; }
        @Override public java.util.logging.Logger getParentLogger() throws SQLFeatureNotSupportedException {
            throw new SQLFeatureNotSupportedException();
        }
        @Override public <T> T unwrap(Class<T> type) throws SQLException {
            throw new SQLFeatureNotSupportedException();
        }
        @Override public boolean isWrapperFor(Class<?> type) { return false; }
    }
}
