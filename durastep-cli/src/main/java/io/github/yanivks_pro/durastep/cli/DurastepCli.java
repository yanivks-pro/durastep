package io.github.yanivks_pro.durastep.cli;

import io.github.yanivks_pro.durastep.store.StoreException;
import java.io.BufferedReader;
import java.io.Console;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.lang.reflect.Constructor;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import picocli.CommandLine;
import picocli.CommandLine.Command;

@Command(
    name = "durastep",
    mixinStandardHelpOptions = true,
    versionProvider = DurastepCli.Version.class,
    description = "Find, inspect and fix Durastep workflows, straight from the database.",
    subcommands = {
      ReadCommands.ListCommand.class,
      ReadCommands.ShowCommand.class,
      ReadCommands.HistoryCommand.class,
      ReadCommands.StatsCommand.class,
      ChangeCommands.SignalCommand.class,
      ChangeCommands.CancelCommand.class,
      ChangeCommands.TerminateCommand.class,
      ChangeCommands.RetryNowCommand.class,
      ChangeCommands.RerunCommand.class
    })
public final class DurastepCli implements Runnable {

  static final int FAILED = 1;

  private final Io io;

  DurastepCli(Io io) {
    this.io = io;
  }

  public static void main(String[] args) {
    Io io =
        new Io(
            new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8)),
            new PrintWriter(System.out, true, StandardCharsets.UTF_8),
            new PrintWriter(System.err, true, StandardCharsets.UTF_8),
            System.getenv(),
            runsInATerminal());
    System.exit(run(io, args));
  }

  private static boolean runsInATerminal() {
    return Optional.ofNullable(System.console()).map(Console::isTerminal).orElse(false);
  }

  static int run(Io io, String... args) {
    CommandLine commandLine = new CommandLine(new DurastepCli(io), new Factory(io));
    commandLine.setOut(io.out());
    commandLine.setErr(io.err());
    commandLine.setCaseInsensitiveEnumValuesAllowed(true);
    commandLine.setExecutionExceptionHandler(
        (error, command, parseResult) -> {
          command.getErr().println("error: " + messageOf(error));
          return FAILED;
        });
    int exitCode = commandLine.execute(args);
    io.out().flush();
    io.err().flush();
    return exitCode;
  }

  @Override
  public void run() {
    new CommandLine(this).usage(io.out());
  }

  private static String messageOf(Exception error) {
    if (error instanceof StoreException && error.getCause() != null) {
      return error.getMessage() + ": " + error.getCause().getMessage();
    }
    return error.getMessage() != null ? error.getMessage() : error.toString();
  }

  static final class Version implements CommandLine.IVersionProvider {
    @Override
    public String[] getVersion() {
      String version = DurastepCli.class.getPackage().getImplementationVersion();
      return new String[] {"durastep " + (version == null ? "(development build)" : version)};
    }
  }

  private static final class Factory implements CommandLine.IFactory {
    private final Io io;
    private final CommandLine.IFactory fallback = CommandLine.defaultFactory();

    Factory(Io io) {
      this.io = io;
    }

    @Override
    public <K> K create(Class<K> type) throws Exception {
      for (Constructor<?> constructor : type.getDeclaredConstructors()) {
        Class<?>[] parameters = constructor.getParameterTypes();
        if (parameters.length == 1 && parameters[0] == Io.class) {
          constructor.setAccessible(true);
          return type.cast(constructor.newInstance(io));
        }
      }
      return fallback.create(type);
    }
  }
}
