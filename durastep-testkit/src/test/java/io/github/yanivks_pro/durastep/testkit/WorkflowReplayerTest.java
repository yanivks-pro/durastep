package io.github.yanivks_pro.durastep.testkit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.yanivks_pro.durastep.api.Workflow;
import io.github.yanivks_pro.durastep.api.WorkflowContext;
import io.github.yanivks_pro.durastep.api.WorkflowFailedException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkflowReplayerTest {

  private static final Path HISTORIES = Path.of("src", "test", "resources", "histories");

  interface Shop {
    String charge(String order);

    void email(String order);

    void sms(String order);
  }

  static final class Before {
    static final class Order implements Workflow<String, String> {
      @Override
      public String run(WorkflowContext ctx, String order) {
        Shop shop = ctx.activities(Shop.class);
        String charge = shop.charge(order);
        shop.email(order);
        return charge + " by email";
      }
    }
  }

  static final class Breaking {
    static final class Order implements Workflow<String, String> {
      @Override
      public String run(WorkflowContext ctx, String order) {
        Shop shop = ctx.activities(Shop.class);
        String charge = shop.charge(order);
        shop.sms(order);
        return charge + " by sms";
      }
    }
  }

  static final class Versioned {
    static final class Order implements Workflow<String, String> {
      @Override
      public String run(WorkflowContext ctx, String order) {
        Shop shop = ctx.activities(Shop.class);
        String charge = shop.charge(order);
        if (ctx.version("send-sms-instead-of-email", 1) >= 1) {
          shop.sms(order);
          return charge + " by sms";
        }
        shop.email(order);
        return charge + " by email";
      }
    }
  }

  static final class NewResult {
    static final class Order implements Workflow<String, String> {
      @Override
      public String run(WorkflowContext ctx, String order) {
        Shop shop = ctx.activities(Shop.class);
        String charge = shop.charge(order);
        shop.email(order);
        return charge + " (emailed)";
      }
    }
  }

  @Test
  void theCodeThatWroteTheHistoriesReplaysThem() {
    assertThatCode(
            () -> WorkflowReplayer.of(Before.Order.class, Before.Order::new).replay(HISTORIES))
        .doesNotThrowAnyException();
  }

  @Test
  void aBreakingChangeIsReportedForEveryHistoryItBreaks() {
    assertThatThrownBy(
            () -> WorkflowReplayer.of(Breaking.Order.class, Breaking.Order::new).replay(HISTORIES))
        .isInstanceOfSatisfying(
            WorkflowReplayException.class,
            e ->
                assertThat(e.failures())
                    .containsExactly(
                        "order-1.json: workflow order-1 called Shop.sms at step 2, but its history"
                            + " has Shop.email there",
                        "order-2.json: workflow order-2 called Shop.sms at step 2, but its history"
                            + " has Shop.email there"))
        .hasMessageStartingWith("2 of 2 histories no longer replay with Order:");
  }

  @Test
  void theSameChangeBehindAVersionCheckReplaysThem() {
    assertThatCode(
            () ->
                WorkflowReplayer.of(Versioned.Order.class, Versioned.Order::new).replay(HISTORIES))
        .doesNotThrowAnyException();
  }

  @Test
  void aFinishedWorkflowMustStillFinishWithTheSameResult() {
    assertThatThrownBy(
            () ->
                WorkflowReplayer.of(NewResult.Order.class, NewResult.Order::new).replay(HISTORIES))
        .isInstanceOfSatisfying(
            WorkflowReplayException.class,
            e ->
                assertThat(e.failures())
                    .containsExactly(
                        "order-1.json: completed with \"ch_order-1 (emailed)\", but the history"
                            + " completed with \"ch_order-1 by email\""));
  }

  @Test
  void aSingleHistoryCanBeReplayedFromAString() throws Exception {
    String history = Files.readString(HISTORIES.resolve("order-2.json"));
    WorkflowReplayer breaking = WorkflowReplayer.of(Breaking.Order.class, Breaking.Order::new);

    assertThatCode(
            () ->
                WorkflowReplayer.of(Before.Order.class, Before.Order::new)
                    .replay("order-2", history))
        .doesNotThrowAnyException();
    assertThatThrownBy(() -> breaking.replay("order-2", history))
        .isInstanceOf(WorkflowReplayException.class)
        .hasMessageContaining("called Shop.sms at step 2");
  }

  @Test
  void anEmptyDirectoryOrABrokenFileIsReportedClearly(@TempDir Path directory) throws Exception {
    WorkflowReplayer replayer = WorkflowReplayer.of(Before.Order.class, Before.Order::new);

    assertThatThrownBy(() -> replayer.replay(directory))
        .isInstanceOf(WorkflowReplayException.class)
        .hasMessageContaining("no .json history files in");

    Files.writeString(directory.resolve("broken.json"), "{\"not\": \"a history\"}");
    Files.writeString(directory.resolve("empty.json"), "[]");
    assertThatThrownBy(() -> replayer.replay(directory))
        .isInstanceOfSatisfying(
            WorkflowReplayException.class,
            e ->
                assertThat(e.failures())
                    .containsExactly(
                        "broken.json: not a history saved with durastep history --json: expected a"
                            + " JSON array of events",
                        "empty.json: the history is empty"));
  }

  static final class Refuses implements Workflow<String, String> {
    @Override
    public String run(WorkflowContext ctx, String order) {
      throw new WorkflowFailedException("refused " + order);
    }
  }

  static final class Waits implements Workflow<String, String> {
    @Override
    public String run(WorkflowContext ctx, String order) {
      return ctx.awaitSignal("go", String.class);
    }
  }

  static final class Throws implements Workflow<String, String> {
    @Override
    public String run(WorkflowContext ctx, String order) {
      throw new IllegalStateException("a bug in the new version");
    }
  }

  @Test
  void aFailedOrCancelledWorkflowMustEndTheSameWay() {
    String failed =
        history(
            event(1, "WORKFLOW_STARTED", "\"o\""),
            event(2, "WORKFLOW_FAILED", "{\"type\":\"x\",\"message\":\"refused o\"}"));
    String cancelled =
        history(
            event(1, "WORKFLOW_STARTED", "\"o\""),
            event(2, "CANCEL_REQUESTED", "{}"),
            event(3, "WORKFLOW_CANCELLED", "{}"));

    assertThatCode(() -> WorkflowReplayer.of(Refuses.class, Refuses::new).replay("o", failed))
        .doesNotThrowAnyException();
    assertThatThrownBy(() -> WorkflowReplayer.of(Waits.class, Waits::new).replay("o", failed))
        .hasMessageContaining("o: failed in the history, but the code now does nothing");
    assertThatThrownBy(
            () -> WorkflowReplayer.of(Refuses.class, Refuses::new).replay("o", cancelled))
        .hasMessageContaining(
            "o: was cancelled in the history, but the code now does [FailWorkflow]");
  }

  @Test
  void aTerminatedWorkflowOnlyHasToReplayNotToEndTheSameWay() {
    String terminated =
        history(
            event(1, "WORKFLOW_STARTED", "\"o\""),
            event(
                2,
                "OPERATOR_ACTION",
                "{\"action\":\"terminate\",\"by\":\"alice\",\"reason\":\"fraud\"}"),
            event(3, "WORKFLOW_CANCELLED", "{}"));

    assertThatCode(() -> WorkflowReplayer.of(Waits.class, Waits::new).replay("o", terminated))
        .doesNotThrowAnyException();
  }

  @Test
  void codeThatNowThrowsIsReported() {
    String started = history(event(1, "WORKFLOW_STARTED", "\"o\""));

    assertThatThrownBy(() -> WorkflowReplayer.of(Throws.class, Throws::new).replay("o", started))
        .hasMessageContaining(
            "o: the code now throws java.lang.IllegalStateException: a bug in the new version");
  }

  private static String history(String... events) {
    return "[" + String.join(",", events) + "]";
  }

  private static String event(int seq, String type, String data) {
    return "{\"seq\":"
        + seq
        + ",\"at\":\"2026-11-17T09:00:0"
        + seq
        + "Z\",\"type\":\""
        + type
        + "\",\"data\":"
        + data
        + "}";
  }
}
