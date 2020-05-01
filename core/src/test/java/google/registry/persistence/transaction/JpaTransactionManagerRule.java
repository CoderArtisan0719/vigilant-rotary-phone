// Copyright 2019 The Nomulus Authors. All Rights Reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package google.registry.persistence.transaction;

import static com.google.common.base.Preconditions.checkState;
import static com.google.common.truth.Truth.assertWithMessage;
import static org.testcontainers.containers.PostgreSQLContainer.POSTGRESQL_PORT;

import com.google.common.base.Charsets;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;
import com.google.common.collect.Streams;
import com.google.common.io.Resources;
import google.registry.persistence.HibernateSchemaExporter;
import google.registry.persistence.NomulusPostgreSql;
import google.registry.persistence.PersistenceModule;
import google.registry.persistence.PersistenceXmlUtility;
import google.registry.util.Clock;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Optional;
import java.util.Properties;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.persistence.EntityManagerFactory;
import org.hibernate.cfg.Environment;
import org.hibernate.jpa.boot.internal.ParsedPersistenceXmlDescriptor;
import org.hibernate.jpa.boot.spi.Bootstrap;
import org.junit.rules.ExternalResource;
import org.testcontainers.containers.JdbcDatabaseContainer;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Base class of JUnit Rules to provision {@link JpaTransactionManagerImpl} backed by {@link
 * PostgreSQLContainer}. This class is not for direct use. Use specialized subclasses, {@link
 * google.registry.persistence.transaction.JpaTestRules.JpaIntegrationTestRule} or {@link
 * JpaTestRules.JpaUnitTestRule} as befits the use case.
 *
 * <p>This rule also replaces the {@link JpaTransactionManagerImpl} provided by {@link
 * TransactionManagerFactory} with the {@link JpaTransactionManagerImpl} generated by the rule
 * itself, so that all SQL queries will be sent to the database instance created by {@link
 * PostgreSQLContainer} to achieve test purpose.
 */
abstract class JpaTransactionManagerRule extends ExternalResource {
  private static final String DB_CLEANUP_SQL_PATH =
      "google/registry/persistence/transaction/cleanup_database.sql";
  private static final String POSTGRES_DB_NAME = "postgres";
  // The type of JDBC connections started by the tests. This string value
  // is documented in PSQL's official user guide.
  private static final String CONNECTION_BACKEND_TYPE = "client backend";
  private static final int ACTIVE_CONNECTIONS_CAP = 5;

  private final Clock clock;
  private final Optional<String> initScriptPath;
  private final ImmutableList<Class> extraEntityClasses;
  private final ImmutableMap userProperties;

  private static final JdbcDatabaseContainer database = create();
  private static final HibernateSchemaExporter exporter =
      HibernateSchemaExporter.create(
          database.getJdbcUrl(), database.getUsername(), database.getPassword());
  // The EntityManagerFactory for the current schema in the test db. This instance may be
  // reused between test methods if the requested schema remains the same.
  private static EntityManagerFactory emf;
  // Hash of the ORM entity names in the current schema in the test db.
  private static int emfEntityHash;

  private JpaTransactionManager cachedTm;
  // Hash of the ORM entity names requested by this rule instance.
  private int entityHash;

  protected JpaTransactionManagerRule(
      Clock clock,
      Optional<String> initScriptPath,
      ImmutableList<Class> extraEntityClasses,
      ImmutableMap<String, String> userProperties) {
    this.clock = clock;
    this.initScriptPath = initScriptPath;
    this.extraEntityClasses = extraEntityClasses;
    this.userProperties = userProperties;
    this.entityHash = getOrmEntityHash(initScriptPath, extraEntityClasses);
  }

  private static JdbcDatabaseContainer create() {
    PostgreSQLContainer container =
        new PostgreSQLContainer(NomulusPostgreSql.getDockerTag())
            .withDatabaseName(POSTGRES_DB_NAME);
    container.start();
    return container;
  }

  private static int getOrmEntityHash(
      Optional<String> initScriptPath, ImmutableList<Class> extraEntityClasses) {
    return Streams.concat(
            Stream.of(initScriptPath.orElse("")),
            extraEntityClasses.stream().map(Class::getCanonicalName))
        .sorted()
        .collect(Collectors.toList())
        .hashCode();
  }

  /**
   * Drops and recreates the 'public' schema and all tables, then creates a new {@link
   * EntityManagerFactory} and save it in {@link #emf}.
   */
  private void recreateSchema() throws Exception {
    if (emf != null) {
      emf.close();
      emf = null;
      emfEntityHash = 0;
      assertReasonableNumDbConnections();
    }
    executeSql(readSqlInClassPath(DB_CLEANUP_SQL_PATH));
    initScriptPath.ifPresent(path -> executeSql(readSqlInClassPath(path)));
    if (!extraEntityClasses.isEmpty()) {
      File tempSqlFile = File.createTempFile("tempSqlFile", ".sql");
      tempSqlFile.deleteOnExit();
      exporter.export(extraEntityClasses, tempSqlFile);
      executeSql(
          new String(Files.readAllBytes(tempSqlFile.toPath()), StandardCharsets.UTF_8));
    }

    ImmutableMap properties = PersistenceModule.providesDefaultDatabaseConfigs();
    if (!userProperties.isEmpty()) {
      // If there are user properties, create a new properties object with these added.
      ImmutableMap.Builder builder = properties.builder();
      builder.putAll(userProperties);
      // Forbid Hibernate push to stay consistent with flyway-based schema management.
      builder.put(Environment.HBM2DDL_AUTO, "none");
      builder.put(Environment.SHOW_SQL, "true");
      properties = builder.build();
    }
    assertReasonableNumDbConnections();
    emf =
        createEntityManagerFactory(
            getJdbcUrl(),
            database.getUsername(),
            database.getPassword(),
            properties,
            extraEntityClasses);
    emfEntityHash = entityHash;
  }

  @Override
  public void before() throws Exception {
    if (entityHash == emfEntityHash) {
      checkState(emf != null, "Missing EntityManagerFactory.");
      resetTablesAndSequences();
    } else {
      recreateSchema();
    }
    JpaTransactionManagerImpl txnManager = new JpaTransactionManagerImpl(emf, clock);
    cachedTm = TransactionManagerFactory.jpaTm();
    TransactionManagerFactory.setJpaTm(txnManager);
  }

  @Override
  public void after() {
    TransactionManagerFactory.setJpaTm(cachedTm);
    cachedTm = null;
  }

  private void resetTablesAndSequences() {
    try (Connection conn = createConnection();
        Statement statement = conn.createStatement()) {
      ResultSet rs =
          statement.executeQuery(
              "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public';");
      ImmutableList.Builder<String> tableNames = new ImmutableList.Builder<>();
      while (rs.next()) {
        tableNames.add('"' + rs.getString(1) + '"');
      }
      String sql =
          String.format(
              "TRUNCATE %s RESTART IDENTITY CASCADE", Joiner.on(',').join(tableNames.build()));
      executeSql(sql);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  /**
   * Asserts that the number of connections to the test database is reasonable, i.e. less than 5.
   * Ideally, it should be 0 if the connection is closed by the test as we don't use a connection
   * pool. However, Hibernate may still maintain some connection by it self. In addition, the
   * metadata table we use to detect active connection may not remove the closed connection
   * immediately. So, we decide to relax the condition to check if the number of active connection
   * is less than 5 to reduce flakiness.
   */
  private void assertReasonableNumDbConnections() {
    try (Connection conn = createConnection();
        Statement statement = conn.createStatement()) {
      // Note: Since we use the admin user (returned by container's getUserName() method)
      // in tests, we need to filter connections by database name and/or backend type to filter out
      // connections for management tasks.
      ResultSet rs =
          statement.executeQuery(
              String.format(
                  "SELECT COUNT(1) FROM pg_stat_activity WHERE usename = '%1s'"
                      + " and datname = '%2s' "
                      + " and backend_type = '%3s'",
                  database.getUsername(), POSTGRES_DB_NAME, CONNECTION_BACKEND_TYPE));
      rs.next();
      assertWithMessage("Too many active connections to database")
          .that(rs.getLong(1))
          .isLessThan(ACTIVE_CONNECTIONS_CAP);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  private static String readSqlInClassPath(String sqlScriptPath) {
    try {
      return Resources.toString(Resources.getResource(sqlScriptPath), Charsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static void executeSql(String sqlScript) {
    try (Connection conn = createConnection();
        Statement statement = conn.createStatement()) {
      statement.execute(sqlScript);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  private static String getJdbcUrl() {
    // Disable Postgres driver use of java.util.logging to reduce noise at startup time
    return "jdbc:postgresql://"
        + database.getContainerIpAddress()
        + ":"
        + database.getMappedPort(POSTGRESQL_PORT)
        + "/"
        + POSTGRES_DB_NAME
        + "?loggerLevel=OFF";
  }

  private static Connection createConnection() {
    final Properties info = new Properties();
    info.put("user", database.getUsername());
    info.put("password", database.getPassword());
    final Driver jdbcDriverInstance = database.getJdbcDriverInstance();
    try {
      return jdbcDriverInstance.connect(getJdbcUrl(), info);
    } catch (SQLException e) {
      throw new RuntimeException(e);
    }
  }

  /** Constructs the {@link EntityManagerFactory} instance. */
  private static EntityManagerFactory createEntityManagerFactory(
      String jdbcUrl,
      String username,
      String password,
      ImmutableMap<String, String> configs,
      ImmutableList<Class> extraEntityClasses) {
    HashMap<String, String> properties = Maps.newHashMap(configs);
    properties.put(Environment.URL, jdbcUrl);
    properties.put(Environment.USER, username);
    properties.put(Environment.PASS, password);
    // Tell Postgresql JDBC driver to expect out-of-band schema change.
    properties.put("hibernate.hikari.dataSource.autosave", "conservative");

    ParsedPersistenceXmlDescriptor descriptor =
        PersistenceXmlUtility.getParsedPersistenceXmlDescriptor();

    extraEntityClasses.stream().map(Class::getName).forEach(descriptor::addClasses);
    return Bootstrap.getEntityManagerFactoryBuilder(descriptor, properties).build();
  }
}
