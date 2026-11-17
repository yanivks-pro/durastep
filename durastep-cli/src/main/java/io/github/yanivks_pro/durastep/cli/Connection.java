package io.github.yanivks_pro.durastep.cli;

import io.github.yanivks_pro.durastep.api.DurastepClient;
import org.postgresql.ds.PGSimpleDataSource;
import picocli.CommandLine.Option;

final class Connection {

  static final String DEFAULT_URL = "jdbc:postgresql://localhost:15432/durastep";

  @Option(
      names = "--url",
      paramLabel = "<jdbc-url>",
      description = "Database URL. Default: $DURASTEP_DB_URL, or " + DEFAULT_URL)
  String url;

  @Option(
      names = "--user",
      paramLabel = "<user>",
      description = "Database user. Default: $DURASTEP_DB_USER, or durastep")
  String user;

  @Option(
      names = "--password",
      paramLabel = "<password>",
      description = "Database password. Default: $DURASTEP_DB_PASSWORD, or durastep")
  String password;

  @Option(
      names = "--schema",
      paramLabel = "<schema>",
      defaultValue = "durastep",
      description = "Schema the engines use. Default: ${DEFAULT-VALUE}")
  String schema;

  @Option(
      names = "--as",
      paramLabel = "<name>",
      description = "Who is acting, for the audit trail. Default: your user name")
  String operator;

  DurastepClient client(Io io) {
    PGSimpleDataSource dataSource = new PGSimpleDataSource();
    dataSource.setUrl(url != null ? url : io.env("DURASTEP_DB_URL").orElse(DEFAULT_URL));
    dataSource.setUser(user != null ? user : io.env("DURASTEP_DB_USER").orElse("durastep"));
    dataSource.setPassword(
        password != null ? password : io.env("DURASTEP_DB_PASSWORD").orElse("durastep"));
    DurastepClient client = DurastepClient.connect(dataSource, schema);
    return operator == null ? client : client.actingAs(operator);
  }
}
