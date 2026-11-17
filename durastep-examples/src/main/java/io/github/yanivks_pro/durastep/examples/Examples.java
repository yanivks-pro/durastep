package io.github.yanivks_pro.durastep.examples;

import io.github.yanivks_pro.durastep.examples.expense.ExpenseExample;
import io.github.yanivks_pro.durastep.examples.order.OrderExample;
import io.github.yanivks_pro.durastep.examples.trip.TripExample;
import java.util.Arrays;
import java.util.Locale;
import org.postgresql.ds.PGSimpleDataSource;

public final class Examples {

  private Examples() {}

  public static void main(String[] args) throws Exception {
    String example = System.getProperty("example", "");
    String scenario = System.getProperty("scenario", "");
    PGSimpleDataSource dataSource = new PGSimpleDataSource();
    dataSource.setUrl(System.getProperty("db.url", "jdbc:postgresql://localhost:15432/durastep"));
    dataSource.setUser(System.getProperty("db.user", "durastep"));
    dataSource.setPassword(System.getProperty("db.password", "durastep"));
    Narrator narrator = new Narrator(System.out);
    switch (example) {
      case "order" ->
          OrderExample.run(dataSource, scenario(OrderExample.Scenario.class, scenario), narrator);
      case "trip" ->
          TripExample.run(dataSource, scenario(TripExample.Scenario.class, scenario), narrator);
      case "expense" ->
          ExpenseExample.run(
              dataSource, scenario(ExpenseExample.Scenario.class, scenario), narrator);
      default -> {
        System.out.println("Choose an example with -Dexample=order|trip|expense");
        System.out.println("  order:   -Dscenario=" + names(OrderExample.Scenario.class));
        System.out.println("  trip:    -Dscenario=" + names(TripExample.Scenario.class));
        System.out.println("  expense: -Dscenario=" + names(ExpenseExample.Scenario.class));
      }
    }
  }

  private static <E extends Enum<E>> E scenario(Class<E> scenarios, String name) {
    E[] all = scenarios.getEnumConstants();
    if (name.isBlank()) {
      return all[0];
    }
    for (E scenario : all) {
      if (nameOf(scenario).equals(name)) {
        return scenario;
      }
    }
    throw new IllegalArgumentException(
        "unknown scenario '" + name + "'; choose one of " + names(scenarios));
  }

  private static <E extends Enum<E>> String names(Class<E> scenarios) {
    return String.join(
        "|", Arrays.stream(scenarios.getEnumConstants()).map(Examples::nameOf).toList());
  }

  private static String nameOf(Enum<?> scenario) {
    return scenario.name().toLowerCase(Locale.ROOT).replace('_', '-');
  }
}
