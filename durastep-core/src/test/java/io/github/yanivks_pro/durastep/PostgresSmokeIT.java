package io.github.yanivks_pro.durastep;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import org.junit.jupiter.api.Test;

class PostgresSmokeIT {

  private static final int MINIMUM_POSTGRES_MAJOR_VERSION = 16;

  @Test
  void startsRealPostgresOfASupportedVersion() throws Exception {
    try (var postgres = TestPostgres.newContainer()) {
      postgres.start();

      try (Connection connection =
          DriverManager.getConnection(
              postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {

        assertThat(connection.getMetaData().getDatabaseMajorVersion())
            .isGreaterThanOrEqualTo(MINIMUM_POSTGRES_MAJOR_VERSION);
      }
    }
  }
}
