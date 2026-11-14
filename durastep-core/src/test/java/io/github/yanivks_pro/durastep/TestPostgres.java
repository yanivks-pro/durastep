package io.github.yanivks_pro.durastep;

import org.testcontainers.postgresql.PostgreSQLContainer;

public final class TestPostgres {

  private static final String IMAGE = System.getProperty("postgres.image", "postgres:16-alpine");

  private TestPostgres() {}

  public static PostgreSQLContainer newContainer() {
    return new PostgreSQLContainer(IMAGE);
  }
}
