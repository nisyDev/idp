package com.empresa.pdi.integration;

import com.empresa.pdi.model.Cargo;
import com.empresa.pdi.model.AcaoPDI;
import com.empresa.pdi.model.AcaoPdiVersao;
import com.empresa.pdi.model.Equipe;
import com.empresa.pdi.model.PDI;
import com.empresa.pdi.model.PdiVersao;
import com.empresa.pdi.model.PdiComponente;
import com.empresa.pdi.model.PdiComponenteVersao;
import com.empresa.pdi.model.Usuario;
import com.empresa.pdi.model.EntregaSubmissao;
import com.empresa.pdi.model.PdiEvento;
import com.fasterxml.jackson.databind.JsonNode;
import org.hibernate.annotations.Immutable;
import org.hibernate.type.BasicType;
import org.hibernate.type.SqlTypes;
import org.hibernate.SessionFactory;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistry;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.hibernate.boot.spi.MetadataImplementor;
import org.hibernate.engine.jdbc.connections.spi.ConnectionProvider;
import org.hibernate.mapping.Column;
import org.hibernate.mapping.Property;
import org.hibernate.mapping.ToOne;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/** Offline mapping check: no Spring, datasource, JDBC access or schema actions. */
class PdiMappingBootstrapTest {
    @Test
    void bootstrapOnlyExplicitMappingsWithoutJdbc() {
        RejectingConnectionProvider connections = new RejectingConnectionProvider();
        StandardServiceRegistry registry = null;
        boolean factoryBuilt = false;
        String stage = "configuration";
        try {
            StandardServiceRegistryBuilder builder = new StandardServiceRegistryBuilder();
            // Discard inherited hibernate.properties/system settings, including URLs.
            builder.getSettings().clear();
            builder.addService(ConnectionProvider.class, connections)
                    .applySetting("hibernate.dialect", "org.hibernate.dialect.PostgreSQLDialect")
                    .applySetting("hibernate.boot.allow_jdbc_metadata_access", false)
                    .applySetting("hibernate.temp.use_jdbc_metadata_defaults", false)
                    .applySetting("hibernate.hbm2ddl.auto", "none")
                    .applySetting("jakarta.persistence.schema-generation.database.action", "none")
                    .applySetting("jakarta.persistence.schema-generation.scripts.action", "none")
                    .applySetting("hibernate.default_schema", "public")
                    .applySetting("hibernate.cache.use_second_level_cache", false)
                    .applySetting("hibernate.cache.use_query_cache", false)
                    .applySetting("hibernate.show_sql", false);
            registry = builder.build();

            stage = "metadata construction";
            MetadataImplementor metadata = (MetadataImplementor) new MetadataSources(registry)
                    .addAnnotatedClass(PDI.class)
                    .addAnnotatedClass(PdiVersao.class)
                    .addAnnotatedClass(AcaoPDI.class)
                    .addAnnotatedClass(AcaoPdiVersao.class)
                    .addAnnotatedClass(PdiComponente.class)
                    .addAnnotatedClass(PdiComponenteVersao.class)
                    .addAnnotatedClass(EntregaSubmissao.class)
                    .addAnnotatedClass(PdiEvento.class)
                    .addAnnotatedClass(Usuario.class)
                    .addAnnotatedClass(Cargo.class)
                    .addAnnotatedClass(Equipe.class)
                    .buildMetadata();
            metadata.validate();

            stage = "metadata assertions";
            assertEquals(Set.of(PDI.class.getName(), PdiVersao.class.getName(),
                            AcaoPDI.class.getName(), AcaoPdiVersao.class.getName(),
                            PdiComponente.class.getName(), PdiComponenteVersao.class.getName(),
                            EntregaSubmissao.class.getName(),
                            PdiEvento.class.getName(),
                            Usuario.class.getName(), Cargo.class.getName(), Equipe.class.getName()),
                    metadata.getEntityBindings().stream()
                            .map(binding -> binding.getClassName()).collect(Collectors.toSet()));

            var pdi = metadata.getEntityBinding(PDI.class.getName());
            Property association = pdi.getProperty("versaoVigente");
            assertFalse(association.isInsertable());
            assertFalse(association.isUpdateable());
            assertEquals(2, association.getColumns().size());
            assertEquals(Set.of("id", "versao_vigente_id"),
                    association.getColumns().stream().map(Column::getName).collect(Collectors.toSet()));
            for (boolean insertable : association.getValue().getColumnInsertability()) {
                assertFalse(insertable, "Association column must be read-only for inserts");
            }
            for (boolean updatable : association.getValue().getColumnUpdateability()) {
                assertFalse(updatable, "Association column must be read-only for updates");
            }
            assertTrue(pdi.getProperty("versaoVigenteId").isInsertable());
            assertTrue(pdi.getProperty("versaoVigenteId").isUpdateable());
            assertEquals("versao_vigente_id",
                    pdi.getProperty("versaoVigenteId").getColumns().get(0).getName());

            var foreignKey = pdi.getTable().getForeignKeys().values().stream()
                    .filter(key -> "fk_pdi_versao_vigente".equals(key.getName()))
                    .findFirst().orElseThrow();
            assertEquals("pdi_versao", foreignKey.getReferencedTable().getName());
            assertEquals("pdi", foreignKey.getTable().getName());
            assertEquals(2, foreignKey.getColumns().size());
            assertEquals(2, foreignKey.getReferencedColumns().size());
            Map<String, String> columnPairs = new HashMap<>();
            for (int i = 0; i < foreignKey.getColumns().size(); i++) {
                String source = foreignKey.getColumns().get(i).getName();
                String target = foreignKey.getReferencedColumns().get(i).getName();
                assertNull(columnPairs.put(source, target), "Duplicate source column");
            }
            assertEquals(Map.of("id", "pdi_id", "versao_vigente_id", "id"), columnPairs);

            var actionVersion = metadata.getEntityBinding(AcaoPdiVersao.class.getName());
            Map<String, Map<String, String>> expectedPairs = Map.of(
                    "fk_acao_versao_plano", Map.of("pdi_id", "pdi_id", "pdi_versao_id", "id"),
                    "fk_acao_versao_identidade", Map.of("pdi_id", "pdi_id", "acao_pdi_id", "id"));
            Map<String, String> targetTables = Map.of(
                    "fk_acao_versao_plano", "pdi_versao",
                    "fk_acao_versao_identidade", "acao_pdi");
            for (var expected : expectedPairs.entrySet()) {
                var key = actionVersion.getTable().getForeignKeys().values().stream()
                        .filter(candidate -> expected.getKey().equals(candidate.getName()))
                        .findFirst().orElseThrow();
                assertEquals("acao_pdi_versao", key.getTable().getName());
                assertEquals(targetTables.get(expected.getKey()), key.getReferencedTable().getName());
                assertEquals(2, key.getColumns().size());
                assertEquals(2, key.getReferencedColumns().size());
                Map<String, String> pairs = new HashMap<>();
                for (int i = 0; i < key.getColumns().size(); i++) {
                    assertNull(pairs.put(key.getColumns().get(i).getName(),
                            key.getReferencedColumns().get(i).getName()), "Duplicate source column");
                }
                assertEquals(expected.getValue(), pairs, expected.getKey());
            }
            for (String name : Set.of("pdiVersao", "acaoPdi")) {
                Property relation = actionVersion.getProperty(name);
                assertFalse(relation.isInsertable(), name);
                assertFalse(relation.isUpdateable(), name);
                assertEquals(2, relation.getColumns().size());
                Set<String> expectedColumns = name.equals("pdiVersao")
                        ? Set.of("pdi_id", "pdi_versao_id") : Set.of("pdi_id", "acao_pdi_id");
                assertEquals(expectedColumns, relation.getColumns().stream()
                        .map(Column::getName).collect(Collectors.toSet()));
                for (boolean insertable : relation.getValue().getColumnInsertability()) {
                    assertFalse(insertable, name);
                }
                for (boolean updatable : relation.getValue().getColumnUpdateability()) {
                    assertFalse(updatable, name);
                }
            }
            for (var scalar : Map.of("pdiId", "pdi_id", "pdiVersaoId", "pdi_versao_id",
                    "acaoPdiId", "acao_pdi_id").entrySet()) {
                Property property = actionVersion.getProperty(scalar.getKey());
                assertTrue(property.isInsertable(), scalar.getKey());
                assertTrue(property.isUpdateable(), scalar.getKey());
                assertEquals(1, property.getColumns().size());
                assertEquals(scalar.getValue(), property.getColumns().get(0).getName());
                assertTrue(property.getValue().getColumnInsertability()[0]);
                assertTrue(property.getValue().getColumnUpdateability()[0]);
            }
            System.out.println("Action version composite FKs and write ownership: verified");

            stage = "component metadata assertions";
            var componentVersion = metadata.getEntityBinding(PdiComponenteVersao.class.getName());
            Map<String, Map<String, String>> componentPairs = Map.of(
                    "fk_componente_versao_acao", Map.of("acao_pdi_id", "acao_pdi_id", "acao_pdi_versao_id", "id"),
                    "fk_componente_versao_identidade", Map.of("acao_pdi_id", "acao_pdi_id", "componente_id", "id"));
            Map<String, String> componentTargets = Map.of(
                    "fk_componente_versao_acao", "acao_pdi_versao",
                    "fk_componente_versao_identidade", "pdi_componente");
            for (var expected : componentPairs.entrySet()) {
                var key = componentVersion.getTable().getForeignKeys().values().stream()
                        .filter(candidate -> expected.getKey().equals(candidate.getName()))
                        .findFirst().orElseThrow();
                assertEquals("pdi_componente_versao", key.getTable().getName());
                assertEquals(componentTargets.get(expected.getKey()), key.getReferencedTable().getName());
                assertEquals(2, key.getColumns().size());
                assertEquals(2, key.getReferencedColumns().size());
                Map<String, String> pairs = new HashMap<>();
                for (int i = 0; i < key.getColumns().size(); i++) {
                    assertNull(pairs.put(key.getColumns().get(i).getName(),
                            key.getReferencedColumns().get(i).getName()), "Duplicate source column");
                }
                assertEquals(expected.getValue(), pairs, expected.getKey());
            }
            for (String name : Set.of("acaoPdiVersao", "componente")) {
                Property relation = componentVersion.getProperty(name);
                assertFalse(relation.isInsertable(), name);
                assertFalse(relation.isUpdateable(), name);
                assertEquals(2, relation.getColumns().size());
                Set<String> expectedColumns = name.equals("acaoPdiVersao")
                        ? Set.of("acao_pdi_id", "acao_pdi_versao_id") : Set.of("acao_pdi_id", "componente_id");
                assertEquals(expectedColumns, relation.getColumns().stream()
                        .map(Column::getName).collect(Collectors.toSet()));
                for (boolean insertable : relation.getValue().getColumnInsertability()) {
                    assertFalse(insertable, name);
                }
                for (boolean updatable : relation.getValue().getColumnUpdateability()) {
                    assertFalse(updatable, name);
                }
            }
            for (var scalar : Map.of("acaoPdiId", "acao_pdi_id", "acaoPdiVersaoId", "acao_pdi_versao_id",
                    "componenteId", "componente_id").entrySet()) {
                Property property = componentVersion.getProperty(scalar.getKey());
                assertTrue(property.isInsertable(), scalar.getKey());
                assertTrue(property.isUpdateable(), scalar.getKey());
                assertEquals(1, property.getColumns().size());
                assertEquals(scalar.getValue(), property.getColumns().get(0).getName());
                assertTrue(property.getValue().getColumnInsertability()[0]);
                assertTrue(property.getValue().getColumnUpdateability()[0]);
            }
            System.out.println("Component version composite FKs and write ownership: verified");

            stage = "submission metadata assertions";
            var submission = metadata.getEntityBinding(EntregaSubmissao.class.getName());
            Map<String, String> submissionColumns = Map.of(
                    "componenteVersao", "componente_versao_id",
                    "submetidaPor", "submetida_por_id",
                    "decididaPor", "decidida_por_id");
            Map<String, String> submissionTargets = Map.of(
                    "componenteVersao", "pdi_componente_versao",
                    "submetidaPor", "usuario", "decididaPor", "usuario");
            for (var expected : submissionColumns.entrySet()) {
                String name = expected.getKey();
                Property property = submission.getProperty(name);
                var relation = assertInstanceOf(ToOne.class, property.getValue());
                assertTrue(relation.isLazy(), name);
                assertEquals(name.equals("decididaPor"), property.isOptional(), name);
                assertEquals(1, property.getColumns().size());
                assertEquals(expected.getValue(), property.getColumns().get(0).getName());
                assertEquals(name.equals("decididaPor"), property.getColumns().get(0).isNullable(), name);
                var keys = submission.getTable().getForeignKeys().values().stream()
                        .filter(key -> key.getColumns().size() == 1
                                && expected.getValue().equals(key.getColumns().get(0).getName()))
                        .toList();
                assertEquals(1, keys.size(), name);
                var key = keys.get(0);
                assertEquals("entrega_submissao", key.getTable().getName());
                assertEquals(submissionTargets.get(name), key.getReferencedTable().getName());
                // For PK references Hibernate may leave referencedColumns empty.
                var targetColumns = key.isReferenceToPrimaryKey()
                        ? key.getReferencedTable().getPrimaryKey().getColumns() : key.getReferencedColumns();
                assertEquals(1, targetColumns.size());
                assertEquals("id", targetColumns.get(0).getName());
                assertTrue(property.isInsertable(), name);
            }
            for (String name : Set.of("componenteVersao", "submetidaPor", "submetidaEm",
                    "comprovacaoTexto", "comprovacaoUrl")) {
                Property property = submission.getProperty(name);
                assertFalse(property.isUpdateable(), name);
                for (boolean updatable : property.getValue().getColumnUpdateability()) {
                    assertFalse(updatable, name);
                }
            }
            for (String name : Set.of("resultado", "encerradaEm", "decididaPor", "justificativa")) {
                Property property = submission.getProperty(name);
                assertTrue(property.isUpdateable(), name);
                for (boolean updatable : property.getValue().getColumnUpdateability()) {
                    assertTrue(updatable, name);
                }
            }
            System.out.println("Submission simple FKs, nullability, LAZY and update ownership: verified");

            stage = "event metadata assertions";
            var event = metadata.getEntityBinding(PdiEvento.class.getName());
            assertTrue(PdiEvento.class.isAnnotationPresent(Immutable.class));
            assertFalse(event.isMutable());
            Map<String, Map<String, String>> eventPairs = Map.of(
                    "fk_evento_versao", Map.of("pdi_id", "pdi_id", "pdi_versao_id", "id"),
                    "fk_evento_acao", Map.of("pdi_id", "pdi_id", "acao_pdi_id", "id"),
                    "fk_evento_acao_versao", Map.of("pdi_versao_id", "pdi_versao_id", "acao_pdi_id", "acao_pdi_id"),
                    "fk_evento_componente", Map.of("acao_pdi_id", "acao_pdi_id", "componente_id", "id"));
            Map<String, String> eventTargets = Map.of(
                    "fk_evento_versao", "pdi_versao", "fk_evento_acao", "acao_pdi",
                    "fk_evento_acao_versao", "acao_pdi_versao", "fk_evento_componente", "pdi_componente");
            for (var expected : eventPairs.entrySet()) {
                var key = event.getTable().getForeignKeys().values().stream()
                        .filter(candidate -> expected.getKey().equals(candidate.getName()))
                        .findFirst().orElseThrow();
                assertEquals("pdi_evento", key.getTable().getName());
                assertEquals(eventTargets.get(expected.getKey()), key.getReferencedTable().getName());
                assertEquals(2, key.getColumns().size());
                assertEquals(2, key.getReferencedColumns().size());
                Map<String, String> pairs = new HashMap<>();
                for (int i = 0; i < key.getColumns().size(); i++) {
                    assertNull(pairs.put(key.getColumns().get(i).getName(),
                            key.getReferencedColumns().get(i).getName()), "Duplicate event source");
                }
                assertEquals(expected.getValue(), pairs, expected.getKey());
            }
            Map<String, Set<String>> eventRelations = Map.of(
                    "pdiVersao", Set.of("pdi_id", "pdi_versao_id"),
                    "acaoPdi", Set.of("pdi_id", "acao_pdi_id"),
                    "acaoPdiVersao", Set.of("pdi_versao_id", "acao_pdi_id"),
                    "componente", Set.of("acao_pdi_id", "componente_id"),
                    "pdi", Set.of("pdi_id"));
            for (var expected : eventRelations.entrySet()) {
                Property property = event.getProperty(expected.getKey());
                assertFalse(property.isInsertable(), expected.getKey());
                assertFalse(property.isUpdateable(), expected.getKey());
                assertEquals(expected.getValue().size(), property.getColumns().size());
                assertEquals(expected.getValue(), property.getColumns().stream()
                        .map(Column::getName).collect(Collectors.toSet()));
                for (boolean writable : property.getValue().getColumnInsertability()) {
                    assertFalse(writable, expected.getKey());
                }
            }
            for (var scalar : Map.of("pdiId", "pdi_id", "pdiVersaoId", "pdi_versao_id",
                    "acaoPdiId", "acao_pdi_id", "componenteId", "componente_id").entrySet()) {
                Property property = event.getProperty(scalar.getKey());
                assertTrue(property.isInsertable(), scalar.getKey());
                assertEquals(1, property.getColumns().size());
                assertEquals(scalar.getValue(), property.getColumns().get(0).getName());
                assertTrue(property.getValue().getColumnInsertability()[0]);
            }
            for (var simple : Map.of("submissao", "submissao_id", "autor", "autor_id").entrySet()) {
                Property property = event.getProperty(simple.getKey());
                assertTrue(property.isInsertable());
                assertTrue(property.getValue().getColumnInsertability()[0]);
                assertEquals(simple.getKey().equals("submissao"), property.isOptional());
                assertTrue(assertInstanceOf(ToOne.class, property.getValue()).isLazy());
                var keys = event.getTable().getForeignKeys().values().stream()
                        .filter(key -> key.getColumns().size() == 1
                                && simple.getValue().equals(key.getColumns().get(0).getName())).toList();
                assertEquals(1, keys.size());
                var key = keys.get(0);
                assertEquals("pdi_evento", key.getTable().getName());
                assertEquals(simple.getKey().equals("submissao") ? "entrega_submissao" : "usuario",
                        key.getReferencedTable().getName());
                var targets = key.isReferenceToPrimaryKey()
                        ? key.getReferencedTable().getPrimaryKey().getColumns() : key.getReferencedColumns();
                assertEquals(1, targets.size());
                assertEquals("id", targets.get(0).getName());
            }
            for (Property property : event.getProperties()) {
                assertFalse(property.isUpdateable(), property.getName());
                for (boolean writable : property.getValue().getColumnUpdateability()) {
                    assertFalse(writable, property.getName());
                }
            }
            for (String name : Set.of("dados", "snapshot")) {
                Property property = event.getProperty(name);
                BasicType<?> jsonType = assertInstanceOf(BasicType.class, property.getType());
                assertTrue(JsonNode.class.isAssignableFrom(jsonType.getReturnedClass()));
                assertEquals(SqlTypes.JSON, jsonType.getJdbcType().getDefaultSqlTypeCode());
                assertEquals(1, property.getColumns().size());
                Column column = property.getColumns().get(0);
                assertEquals("jsonb", column.getSqlType(metadata));
                assertEquals(name.equals("snapshot"), column.isNullable());
            }
            System.out.println("Event four composite FKs, two simple FKs, JSONB and immutability: verified");

            stage = "SessionFactory construction";
            try (SessionFactory factory = metadata.buildSessionFactory()) {
                factoryBuilt = true;
                assertFalse(factory.isClosed());
                assertEquals(Set.of(PDI.class, PdiVersao.class, AcaoPDI.class, AcaoPdiVersao.class,
                                PdiComponente.class, PdiComponenteVersao.class,
                                EntregaSubmissao.class,
                                PdiEvento.class,
                                Usuario.class, Cargo.class, Equipe.class),
                        factory.getMetamodel().getEntities().stream()
                                .map(entity -> entity.getJavaType()).collect(Collectors.toSet()));
            }
        } finally {
            try {
                if (registry != null) {
                    StandardServiceRegistryBuilder.destroy(registry);
                }
            } finally {
                System.out.printf("Offline bootstrap: stage=%s; SessionFactory built=%s; JDBC attempts=%d%n",
                        stage, factoryBuilt, connections.attempts.get());
                assertEquals(0, connections.attempts.get(), "No JDBC connection may be requested");
            }
        }
    }

    private static final class RejectingConnectionProvider implements ConnectionProvider {
        private final AtomicInteger attempts = new AtomicInteger();

        @Override
        public Connection getConnection() throws SQLException {
            attempts.incrementAndGet();
            throw new SQLException("JDBC connections are forbidden in the offline mapping test");
        }

        @Override
        public void closeConnection(Connection connection) throws SQLException {
            throw new SQLException("No JDBC connection should exist");
        }

        @Override
        public boolean supportsAggressiveRelease() {
            return false;
        }

        @Override
        public boolean isUnwrappableAs(Class<?> type) {
            return type.isInstance(this);
        }

        @Override
        public <T> T unwrap(Class<T> type) {
            if (type.isInstance(this)) {
                return type.cast(this);
            }
            throw new IllegalArgumentException("Unsupported connection provider unwrap");
        }
    }
}
