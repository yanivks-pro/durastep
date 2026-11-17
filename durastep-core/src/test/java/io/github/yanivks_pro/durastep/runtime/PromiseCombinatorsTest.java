package io.github.yanivks_pro.durastep.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.yanivks_pro.durastep.api.ActivityFailedException;
import io.github.yanivks_pro.durastep.api.Promise;
import io.github.yanivks_pro.durastep.payload.JsonPayloadConverter;
import io.github.yanivks_pro.durastep.runtime.Command.CompleteWorkflow;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class PromiseCombinatorsTest {

  interface Quotes {
    String fromA(String trip);

    String fromB(String trip);
  }

  private final JsonPayloadConverter json = new JsonPayloadConverter();
  private final WorkflowRuntime runtime = new WorkflowRuntime(json);

  private static final WorkflowDefinition<String, String> FIRST_QUOTE =
      new WorkflowDefinition<>(
          "FirstQuote",
          String.class,
          () ->
              (ctx, trip) -> {
                Quotes quotes = ctx.activities(Quotes.class);
                Promise<String> a = ctx.async(() -> quotes.fromA(trip));
                Promise<String> b = ctx.async(() -> quotes.fromB(trip));
                Promise<String> first = Promise.anyOf(a, b);
                try {
                  return first.get();
                } catch (ActivityFailedException e) {
                  return "first quote failed: " + e.getMessage();
                }
              });

  private static final WorkflowDefinition<String, String> ALL_QUOTES =
      new WorkflowDefinition<>(
          "AllQuotes",
          String.class,
          () ->
              (ctx, trip) -> {
                Quotes quotes = ctx.activities(Quotes.class);
                Promise<String> a = ctx.async(() -> quotes.fromA(trip));
                Promise<String> b = ctx.async(() -> quotes.fromB(trip));
                try {
                  Promise.allOf(a, b).get();
                } catch (ActivityFailedException e) {
                  return "a quote failed: " + e.getMessage();
                }
                return a.get() + "," + b.get();
              });

  @Test
  void anyOfPicksTheBranchThatFinishedFirstInTheHistory() {
    assertThat(decide(FIRST_QUOTE, bothAsked())).isEmpty();
    assertThat(decide(FIRST_QUOTE, bothAsked().completed(3, "B: 90")))
        .containsExactly(complete("B: 90"));
    assertThat(decide(FIRST_QUOTE, bothAsked().completed(3, "B: 90").completed(2, "A: 80")))
        .containsExactly(complete("B: 90"));
    assertThat(decide(FIRST_QUOTE, bothAsked().completed(2, "A: 80").completed(3, "B: 90")))
        .containsExactly(complete("A: 80"));
  }

  @Test
  void anyOfPicksTheFirstInTheHistoryEvenWhenSeveralFinishedBeforeItWasCalled() {
    WorkflowDefinition<String, String> asksLater =
        new WorkflowDefinition<>(
            "AsksLater",
            String.class,
            () ->
                (ctx, trip) -> {
                  Quotes quotes = ctx.activities(Quotes.class);
                  Promise<String> a = ctx.async(() -> quotes.fromA(trip));
                  Promise<String> b = ctx.async(() -> quotes.fromB(trip));
                  ctx.sleep(Duration.ofMinutes(1));
                  return Promise.anyOf(a, b).get();
                });
    History bothDoneWhileSleeping =
        History.startedWith("trip-1")
            .timerStarted(Duration.ofMinutes(1))
            .scheduledIn("0.1", "Quotes.fromA", "trip-1")
            .scheduledIn("0.2", "Quotes.fromB", "trip-1")
            .completed(4, "B: 90")
            .completed(3, "A: 80")
            .timerFired(2);

    assertThat(decide(asksLater, bothDoneWhileSleeping)).containsExactly(complete("B: 90"));
  }

  @Test
  void anyOfRethrowsTheFailureOfTheBranchThatFinishedFirst() {
    assertThat(decide(FIRST_QUOTE, bothAsked().failed(2, "com.example.Down", "A is down")))
        .singleElement()
        .isInstanceOfSatisfying(
            CompleteWorkflow.class,
            done ->
                assertThat((String) json.fromPayload(done.result(), String.class))
                    .startsWith("first quote failed")
                    .contains("A is down"));
  }

  @Test
  void allOfWaitsForEveryBranch() {
    assertThat(decide(ALL_QUOTES, bothAsked().completed(2, "A: 80"))).isEmpty();
    assertThat(decide(ALL_QUOTES, bothAsked().completed(2, "A: 80").completed(3, "B: 90")))
        .containsExactly(complete("A: 80,B: 90"));
  }

  @Test
  void allOfRethrowsTheFailureThatCameFirstInTheHistory() {
    History bothFailed =
        bothAsked()
            .failed(3, "com.example.Down", "B is down")
            .failed(2, "com.example.Down", "A is down");

    assertThat(decide(ALL_QUOTES, bothFailed))
        .singleElement()
        .isInstanceOfSatisfying(
            CompleteWorkflow.class,
            done ->
                assertThat((String) json.fromPayload(done.result(), String.class))
                    .contains("B is down")
                    .doesNotContain("A is down"));
  }

  @Test
  void branchesThatOnlyWaitForEachOtherBlockTheWorkflowWithAClearMessage() {
    WorkflowDefinition<String, String> waitsForItself =
        new WorkflowDefinition<>(
            "WaitsForItself",
            String.class,
            () ->
                (ctx, trip) -> {
                  AtomicReference<Promise<String>> self = new AtomicReference<>();
                  self.set(ctx.async(() -> self.get().get()));
                  return self.get().get();
                });

    assertThatThrownBy(() -> decide(waitsForItself, History.startedWith("trip-1")))
        .isInstanceOf(WorkflowBlockedException.class)
        .rootCause()
        .hasMessageContaining("branches 0, 0.1 are only waiting for each other");
  }

  @Test
  void onlyPromisesFromThisWorkflowCanBeCombined() {
    Promise<String> foreign =
        new Promise<>() {
          @Override
          public String get() {
            return "foreign";
          }

          @Override
          public boolean isDone() {
            return true;
          }
        };
    WorkflowDefinition<String, String> combinesForeign =
        new WorkflowDefinition<>(
            "CombinesForeign", String.class, () -> (ctx, trip) -> Promise.anyOf(foreign).get());

    assertThatThrownBy(() -> decide(combinesForeign, History.startedWith("trip-1")))
        .isInstanceOf(WorkflowBlockedException.class)
        .rootCause()
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void combiningPromisesOutsideWorkflowCodeIsAnError() {
    assertThatThrownBy(() -> Promise.allOf())
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("only be called from workflow code");
  }

  private static History bothAsked() {
    return History.startedWith("trip-1")
        .scheduledIn("0.1", "Quotes.fromA", "trip-1")
        .scheduledIn("0.2", "Quotes.fromB", "trip-1");
  }

  private List<Command> decide(WorkflowDefinition<String, String> workflow, History history) {
    return runtime.decide(workflow, "trip-1", history.entries());
  }

  private CompleteWorkflow complete(String result) {
    return new CompleteWorkflow(json.toPayload(result));
  }
}
