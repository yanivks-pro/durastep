package io.github.yanivks_pro.durastep;

import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.testcontainers.postgresql.PostgreSQLContainer;

public final class TestPostgres {

  private static final String IMAGE = System.getProperty("postgres.image", "postgres:16-alpine");

  private TestPostgres() {}

  public static PostgreSQLContainer newContainer() {
    return new PostgreSQLContainer(IMAGE);
  }

  public static boolean fullyMigrated(DataSource dataSource) {
    try {
      Flyway flyway =
          Flyway.configure()
              .dataSource(dataSource)
              .schemas("durastep")
              .locations("classpath:durastep/db/migration")
              .load();
      return flyway.info().applied().length > 0 && flyway.info().pending().length == 0;
    } catch (FlywayException e) {
      return false;
    }
  }
}
