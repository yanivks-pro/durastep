package io.github.yanivks_pro.durastep.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.yanivks_pro.durastep.api.CancelledException;
import io.github.yanivks_pro.durastep.api.Promise;
import io.github.yanivks_pro.durastep.api.Saga;
import io.github.yanivks_pro.durastep.payload.JsonPayloadConverter;
import io.github.yanivks_pro.durastep.runtime.Command.CancelWorkflow;
import io.github.yanivks_pro.durastep.runtime.Command.CompleteWorkflow;
import io.github.yanivks_pro.durastep.runtime.Command.ScheduleActivity;
import io.github.yanivks_pro.durastep.runtime.HistoryEvent.ActivityCompleted;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.Size;
import net.jqwik.api.statistics.Statistics;

class ReplayProperties {

  interface Steps {
    String step(String branch, int number);
  }

  interface Trips {
    String book(String what);

    void cancel(String what);
  }

  private static final JsonPayloadConverter JSON = new JsonPayloadConverter();

  private static final WorkflowDefinition<String, String> PARALLEL =
      new WorkflowDefinition<>(
          "Parallel",
          String.class,
          () ->
              (ctx, trip) -> {
                Steps steps = ctx.activities(Steps.class);
                Promise<String> a =
                    ctx.async(() -> steps.step("a", 1) + steps.step("a", 2) + steps.step("a", 3));
                Promise<String> b = ctx.async(() -> steps.step("b", 1) + steps.step("b", 2));
                Promise<String> c =
                    ctx.async(
                        () -> {
                          String first = steps.step("c", 1);
                          Promise<String> inner = ctx.async(() -> steps.step("inner", 1));
                          return first + steps.step("c", 2) + inner.get();
                        });
                Promise<String> x = ctx.async(() -> steps.step("x", 1));
                Promise<String> y = ctx.async(() -> steps.step("y", 1));
                String quote = Promise.anyOf(x, y).get();
                Promise.allOf(a, b, c).get();
                String quoteAskedLater = Promise.anyOf(y, x).get();
                return a.get()
                    + "|"
                    + b.get()
                    + "|"
                    + c.get()
                    + "|"
                    + quote
                    + "|"
                    + quoteAskedLater;
              });

  private static final WorkflowDefinition<String, String> TRIP =
      new WorkflowDefinition<>(
          "Trip",
          String.class,
          () ->
              (ctx, trip) -> {
                Trips trips = ctx.activities(Trips.class);
                Saga saga = ctx.saga();
                try {
                  saga.addCompensation(() -> trips.cancel("flight"));
                  saga.addCompensation(() -> trips.cancel("hotel"));
                  Promise<String> flight = ctx.async(() -> trips.book("flight"));
                  Promise<String> hotel =
                      ctx.async(
                          () -> {
                            ctx.sleep(Duration.ofMinutes(5));
                            return trips.book("hotel");
                          });
                  Promise.allOf(flight, hotel).get();
                  Optional<String> confirmed =
                      ctx.awaitSignal("confirm", String.class, Duration.ofHours(1));
                  String car = trips.book("car " + confirmed.orElse("unconfirmed"));
                  return flight.get() + ", " + hotel.get() + ", " + car;
                } catch (CancelledException e) {
                  saga.compensate();
                  throw e;
                }
              });

  @Property
  void parallelBranchesGiveTheRightResultAndReplayIdenticallyInAnyOrder(
      @ForAll @Size(max = 300) List<@IntRange(min = 0, max = 99) Integer> choices) {
    SimulatedEngine engine =
        new SimulatedEngine(PARALLEL, "trip-1", "trip-1", ReplayProperties::stepResult)
            .run(choices);

    String firstQuote = firstQuoteIn(engine);
    assertThat(engine.ending()).isInstanceOf(CompleteWorkflow.class);
    assertThat(engine.result())
        .isEqualTo("a1a2a3|b1b2|c1c2inner1|" + firstQuote + "|" + firstQuote);
    engine.assertNoActivityWasScheduledTwice();
    engine.assertEveryDecisionReplaysIdentically();
  }

  @Property
  void resultsSignalsTimersCancellationAndCrashesInAnyMixEndConsistently(
      @ForAll @Size(max = 300) List<@IntRange(min = 0, max = 99) Integer> choices) {
    SimulatedEngine engine =
        new SimulatedEngine(TRIP, "trip-1", "trip-1", ReplayProperties::tripResult)
            .withSignal("confirm", "yes")
            .allowingCancellation()
            .run(choices);

    engine.assertNoActivityWasScheduledTwice();
    engine.assertEveryDecisionReplaysIdentically();
    Statistics.label("ending").collect(engine.ending().getClass().getSimpleName());
    Statistics.label("with crashed decisions").collect(engine.crashedDecisions() > 0);
    Statistics.label("ending")
        .coverage(
            coverage -> {
              coverage.check("CancelWorkflow").percentage(share -> share >= 5);
              coverage.check("CompleteWorkflow").percentage(share -> share >= 5);
            });
    Statistics.label("with crashed decisions")
        .coverage(coverage -> coverage.check(true).percentage(share -> share >= 50));
    List<String> scheduled = engine.scheduledActivities();
    if (engine.ending() instanceof CancelWorkflow) {
      assertThat(engine.cancelRequested()).isTrue();
      assertThat(scheduled).containsSubsequence("Trips.cancel[hotel]", "Trips.cancel[flight]");
    } else {
      assertThat(engine.ending()).isInstanceOf(CompleteWorkflow.class);
      assertThat(engine.result())
          .matches("booked flight, booked hotel, booked car (yes|unconfirmed)");
      assertThat(scheduled).doesNotContain("Trips.cancel[hotel]", "Trips.cancel[flight]");
    }
  }

  private static Object stepResult(ScheduleActivity activity) {
    List<?> arguments = (List<?>) JSON.fromPayload(activity.arguments(), List.class);
    return arguments.get(0).toString() + arguments.get(1);
  }

  private static Object tripResult(ScheduleActivity activity) {
    if (activity.activity().equals("Trips.cancel")) {
      return null;
    }
    List<?> arguments = (List<?>) JSON.fromPayload(activity.arguments(), List.class);
    return "booked " + arguments.get(0);
  }

  private static String firstQuoteIn(SimulatedEngine engine) {
    for (HistoryEntry entry : engine.history()) {
      if (entry.event() instanceof ActivityCompleted done) {
        String result = (String) JSON.fromPayload(done.result(), String.class);
        if (result.equals("x1") || result.equals("y1")) {
          return result;
        }
      }
    }
    throw new AssertionError("no quote completed");
  }
}
