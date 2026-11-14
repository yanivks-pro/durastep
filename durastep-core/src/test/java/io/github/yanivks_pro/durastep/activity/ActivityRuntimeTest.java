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
import io.github.yanivks_pro.durastep.payload.JsonPayloadConverter;
import io.github.yanivks_pro.durastep.payload.Payload;
import io.github.yanivks_pro.durastep.runtime.Failure;
import org.junit.jupiter.api.Test;

class ActivityRuntimeTest {

  private final JsonPayloadConverter json = new JsonPayloadConverter();
  private final FakePayments payments = new FakePayments();
  private final ActivityRuntime runtime =
      new ActivityRuntime(new ActivityRegistry().register(PaymentActivities.class, payments), json);

  @Test
  void convertsTheArgumentsCallsTheActivityAndReturnsItsResultAsJson() {
    Payload arguments = json.toArguments(new Object[] {new Card("4242424242424242", 2030), 4200L});

    ActivityResult result = runtime.execute("PaymentActivities.charge", arguments, "order-42", 2);

    assertThat(result).isInstanceOf(Completed.class);
    assertThat(json.fromPayload(((Completed) result).result(), Charge.class))
        .isEqualTo(new Charge("ch_4242", 4200));
  }

  @Test
  void aVoidActivityCompletesWithJsonNull() {
    ActivityResult result =
        runtime.execute(
            "PaymentActivities.refund", json.toArguments(new Object[] {"ch_4242"}), "order-42", 4);

    assertThat(result).isEqualTo(new Completed(new Payload("null")));
    assertThat(payments.refunded).containsExactly("ch_4242 for order-42");
  }

  @Test
  void anExceptionFromTheActivityBecomesAFailureWithTheOriginalType() {
    Payload arguments = json.toArguments(new Object[] {new Card("4242424242424242", 2020), 4200L});

    ActivityResult result = runtime.execute("PaymentActivities.charge", arguments, "order-42", 2);

    assertThat(result)
        .isEqualTo(
            new Failed(new Failure(CardDeclinedException.class.getName(), "card expired in 2020")));
  }

  @Test
  void theIdempotencyKeyNamesTheWorkflowAndTheStep() {
    ActivityResult result =
        runtime.execute("PaymentActivities.keyOfThisCall", json.toArguments(null), "order-42", 2);

    assertThat(result).isEqualTo(new Completed(json.toPayload("order-42/activity-2")));
  }

  @Test
  void theContextIsNotAvailableOutsideAnActivity() {
    assertThatThrownBy(ActivityContext::current)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("inside an activity");
  }

  @Test
  void anUnknownActivityFailsWithAClearMessage() {
    ActivityResult result =
        runtime.execute("PaymentActivities.teleport", json.toArguments(null), "order-42", 2);

    assertThat(result)
        .isEqualTo(
            new Failed(
                new Failure(
                    IllegalStateException.class.getName(),
                    "no implementation is registered for activity PaymentActivities.teleport")));
  }

  @Test
  void argumentsThatDoNotMatchTheMethodFail() {
    ActivityResult result =
        runtime.execute(
            "PaymentActivities.refund", json.toArguments(new Object[] {"a", "b"}), "order-42", 4);

    assertThat(result).isInstanceOf(Failed.class);
    assertThat(((Failed) result).failure().message()).contains("expected a JSON array of 1");
  }
}
