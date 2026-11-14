package io.github.yanivks_pro.durastep.activity;

import io.github.yanivks_pro.durastep.api.ActivityContext;
import java.util.ArrayList;
import java.util.List;

final class PaymentExample {

  private PaymentExample() {}

  record Card(String number, int expiryYear) {}

  record Charge(String id, long cents) {}

  static final class CardDeclinedException extends Exception {
    private static final long serialVersionUID = 1L;

    CardDeclinedException(String message) {
      super(message);
    }
  }

  interface PaymentActivities {
    Charge charge(Card card, long cents) throws CardDeclinedException;

    void refund(String chargeId);

    String keyOfThisCall();
  }

  static final class FakePayments implements PaymentActivities {
    final List<String> refunded = new ArrayList<>();

    @Override
    public Charge charge(Card card, long cents) throws CardDeclinedException {
      if (card.expiryYear() < 2026) {
        throw new CardDeclinedException("card expired in " + card.expiryYear());
      }
      return new Charge("ch_" + card.number().substring(card.number().length() - 4), cents);
    }

    @Override
    public void refund(String chargeId) {
      refunded.add(chargeId + " for " + ActivityContext.current().workflowId());
    }

    @Override
    public String keyOfThisCall() {
      return ActivityContext.current().idempotencyKey();
    }
  }
}
