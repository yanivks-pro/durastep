package io.github.yanivks_pro.durastep.benchmarks;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.github.yanivks_pro.durastep.api.DurastepEngine;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;

public final class EngineProcess {

  static final String READY = "engine ready";
  static final String CPU = "cpu";

  private EngineProcess() {}

  public static void main(String... args) throws Exception {
    HikariConfig config = new HikariConfig();
    config.setJdbcUrl(args[0]);
    config.setUsername(args[1]);
    config.setPassword(args[2]);
    config.setMaximumPoolSize(Integer.parseInt(args[3]));
    EngineSettings settings =
        new EngineSettings(Integer.parseInt(args[4]), Integer.parseInt(args[5]));
    try (HikariDataSource dataSource = new HikariDataSource(config);
        DurastepEngine engine = settings.engine(dataSource)) {
      engine.start();
      System.out.println(READY);
      System.out.flush();
      BufferedReader commands =
          new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
      String command;
      while ((command = commands.readLine()) != null) {
        if (command.equals(CPU)) {
          System.out.println(CPU + " " + cpuNanos());
          System.out.flush();
        }
      }
    }
  }

  private static long cpuNanos() {
    return ((com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean())
        .getProcessCpuTime();
  }
}
