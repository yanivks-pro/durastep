package io.github.yanivks_pro.durastep;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;

class PostgresSmokeIT {

  @Test
  void startsRealPostgresAndRunsAQuery() throws Exception {
    try (var postgres = new PostgreSQLContainer("postgres:16-alpine")) {
      postgres.start();

      try (Connection connection =
              DriverManager.getConnection(
                  postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
          Statement statement = connection.createStatement();
          ResultSet result = statement.executeQuery("SELECT version()")) {

        assertThat(result.next()).isTrue();
        assertThat(result.getString(1)).startsWith("PostgreSQL 16");
      }
    }
  }
}
