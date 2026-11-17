package io.github.yanivks_pro.durastep.examples;

import javax.sql.DataSource;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

final class ExamplePostgres {

  private static final String IMAGE = System.getProperty("postgres.image", "postgres:16-alpine");

  private ExamplePostgres() {}

  static PostgreSQLContainer newContainer() {
    return new PostgreSQLContainer(IMAGE);
  }

  static DataSource dataSourceFor(PostgreSQLContainer postgres) {
    PGSimpleDataSource dataSource = new PGSimpleDataSource();
    dataSource.setUrl(postgres.getJdbcUrl());
    dataSource.setUser(postgres.getUsername());
    dataSource.setPassword(postgres.getPassword());
    return dataSource;
  }
}
