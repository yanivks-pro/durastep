package io.github.yanivks_pro.durastep.activity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.yanivks_pro.durastep.activity.ActivityResult.Completed;
import io.github.yanivks_pro.durastep.activity.ActivityResult.Failed;
import io.github.yanivks_pro.durastep.activity.PaymentExample.Card;
import io.github.yanivks_pro.durastep.activity.PaymentExample.CardDeclinedException;
import io.github.yanivks_pro.durastep.activity.PaymentExample.Charge;
import io.github.yanivks_pro.durastep.activity.PaymentExample.FakePayments;
import io.github.yanivks_pro.durastep.activity.PaymentExample.PaymentActivities;
import io.github.yanivks_pro.durastep.api.ActivityContext;
import io.github.yanivks_pro.durastep.api.ActivityOptions;
import io.github.yanivks_pro.durastep.api.ActivityTimeoutException;
import io.github.yanivks_pro.durastep.api.RetryPolicy;
import io.github.yanivks_pro.durastep.payload.JsonPayloadConverter;
import io.github.yanivks_pro.durastep.payload.Payload;
import io.github.yanivks_pro.durastep.runtime.Failure;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class ActivityRuntimeTest {

  private static final ActivityOptions DEFAULT = ActivityOptions.DEFAULT;

  private final JsonPayloadConverter json = new JsonPayloadConverter();
  private final FakePayments payments = new FakePayments();
  private final ActivityRuntime runtime =
      new ActivityRuntime(
          new ActivityRegistry()
              .register(PaymentActivities.class, payments)
              .register(SlowActivities.class, new Slow()),
          json);

  interface SlowActivities {
    String waitFor(long millis);
  }

  static final class Slow implements SlowActivities {
    final CountDownLatch interrupted = new CountDownLatch(1);
    final AtomicBoolean finished = new AtomicBoolean();

    @Override
    public String waitFor(long millis) {
      try {
        Thread.sleep(millis);
        finished.set(true);
        return "done";
      } catch (InterruptedException e) {
        interrupted.countDown();
        Thread.currentThread().interrupt();
        throw new IllegalStateException("interrupted", e);
      }
    }
  }

  @Test
  void convertsTheArgumentsCallsTheActivityAndReturnsItsResultAsJson() throws Exception {
    Payload arguments = json.toArguments(new Object[] {new Card("4242424242424242", 2030), 4200L});

    ActivityResult result =
        runtime.execute("PaymentActivities.charge", arguments, "order-42", 2, DEFAULT);

    assertThat(result).isInstanceOf(Completed.class);
    assertThat(json.fromPayload(((Completed) result).result(), Charge.class))
        .isEqualTo(new Charge("ch_4242", 4200));
  }

  @Test
  void aVoidActivityCompletesWithJsonNull() throws Exception {
    ActivityResult result =
        runtime.execute(
            "PaymentActivities.refund",
            json.toArguments(new Object[] {"ch_4242"}),
            "order-42",
            4,
            DEFAULT);

    assertThat(result).isEqualTo(new Completed(new Payload("null")));
    assertThat(payments.refunded).containsExactly("ch_4242 for order-42");
  }

  @Test
  void anExceptionFromTheActivityIsARetryableFailureWithTheOriginalType() throws Exception {
    Payload arguments = json.toArguments(new Object[] {new Card("4242424242424242", 2020), 4200L});

    ActivityResult result =
        runtime.execute("PaymentActivities.charge", arguments, "order-42", 2, DEFAULT);

    assertThat(result)
        .isEqualTo(
            new Failed(
                new Failure(CardDeclinedException.class.getName(), "card expired in 2020"), true));
  }

  @Test
  void anErrorListedAsNonRetryableIsNotRetryable() throws Exception {
    ActivityOptions noRetryOnDecline =
        ActivityOptions.builder()
            .retryPolicy(RetryPolicy.builder().nonRetryable(CardDeclinedException.class).build())
            .build();
    Payload arguments = json.toArguments(new Object[] {new Card("4242424242424242", 2020), 4200L});

    ActivityResult result =
        runtime.execute("PaymentActivities.charge", arguments, "order-42", 2, noRetryOnDecline);

    assertThat(result).isInstanceOf(Failed.class);
    assertThat(((Failed) result).retryable()).isFalse();
  }

  @Test
  void anAttemptThatRunsTooLongIsInterruptedAndFailsWithARetryableTimeout() throws Exception {
    Slow slow = new Slow();
    ActivityRuntime withSlow =
        new ActivityRuntime(new ActivityRegistry().register(SlowActivities.class, slow), json);
    ActivityOptions shortLimit =
        ActivityOptions.builder().attemptTimeout(Duration.ofMillis(200)).build();

    long started = System.nanoTime();
    ActivityResult result =
        withSlow.execute(
            "SlowActivities.waitFor",
            json.toArguments(new Object[] {60_000L}),
            "order-42",
            2,
            shortLimit);
    Duration took = Duration.ofNanos(System.nanoTime() - started);

    assertThat(result)
        .isEqualTo(
            new Failed(
                new Failure(
                    ActivityTimeoutException.class.getName(),
                    "SlowActivities.waitFor did not finish within PT0.2S"),
                true));
    assertThat(took).isLessThan(Duration.ofSeconds(5));
    assertThat(slow.interrupted.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(slow.finished).isFalse();
  }

  @Test
  void anAttemptWithinItsLimitCompletesNormally() throws Exception {
    ActivityOptions generous =
        ActivityOptions.builder().attemptTimeout(Duration.ofSeconds(5)).build();

    ActivityResult result =
        runtime.execute(
            "SlowActivities.waitFor",
            json.toArguments(new Object[] {50L}),
            "order-42",
            2,
            generous);

    assertThat(result).isEqualTo(new Completed(json.toPayload("done")));
  }

  @Test
  void theIdempotencyKeyNamesTheWorkflowAndTheStep() throws Exception {
    ActivityResult result =
        runtime.execute(
            "PaymentActivities.keyOfThisCall", json.toArguments(null), "order-42", 2, DEFAULT);

    assertThat(result).isEqualTo(new Completed(json.toPayload("order-42/activity-2")));
  }

  @Test
  void theContextIsNotAvailableOutsideAnActivity() {
    assertThatThrownBy(ActivityContext::current)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("inside an activity");
  }

  @Test
  void anUnknownActivityFailsWithoutRetrying() throws Exception {
    ActivityResult result =
        runtime.execute(
            "PaymentActivities.teleport", json.toArguments(null), "order-42", 2, DEFAULT);

    assertThat(result)
        .isEqualTo(
            new Failed(
                new Failure(
                    IllegalStateException.class.getName(),
                    "no implementation is registered for activity PaymentActivities.teleport"),
                false));
  }

  @Test
  void argumentsThatDoNotMatchTheMethodFailWithoutRetrying() throws Exception {
    ActivityResult result =
        runtime.execute(
            "PaymentActivities.refund",
            json.toArguments(new Object[] {"a", "b"}),
            "order-42",
            4,
            DEFAULT);

    assertThat(result).isInstanceOf(Failed.class);
    assertThat(((Failed) result).failure().message()).contains("expected a JSON array of 1");
    assertThat(((Failed) result).retryable()).isFalse();
  }
}
