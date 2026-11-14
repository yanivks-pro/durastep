package io.github.yanivks_pro.durastep.activity;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.yanivks_pro.durastep.activity.PaymentExample.FakePayments;
import io.github.yanivks_pro.durastep.activity.PaymentExample.PaymentActivities;
import org.junit.jupiter.api.Test;

class ActivityRegistryTest {

  private final ActivityRegistry registry = new ActivityRegistry();

  @Test
  void rejectsActivitiesDeclaredOnAClass() {
    assertThatThrownBy(() -> registry.register(FakePayments.class, new FakePayments()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("must be declared on an interface");
  }

  @Test
  @SuppressWarnings({"unchecked", "rawtypes"})
  void rejectsAnImplementationOfTheWrongInterface() {
    Class raw = PaymentActivities.class;

    assertThatThrownBy(() -> registry.register(raw, "not a payment service"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("does not implement");
  }

  interface Overloaded {
    void send(String to);

    void send(String to, String subject);
  }

  @Test
  void rejectsOverloadedActivityMethods() {
    Overloaded implementation =
        new Overloaded() {
          @Override
          public void send(String to) {}

          @Override
          public void send(String to, String subject) {}
        };

    assertThatThrownBy(() -> registry.register(Overloaded.class, implementation))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Overloaded.send is overloaded");
  }

  static final class Billing {
    interface Notifications {
      void notify(String message);
    }
  }

  static final class Shipping {
    interface Notifications {
      void notify(String message);
    }
  }

  @Test
  void rejectsTwoInterfacesWithTheSameShortName() {
    registry.register(Billing.Notifications.class, message -> {});

    assertThatThrownBy(() -> registry.register(Shipping.Notifications.class, message -> {}))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Notifications is used by both");
  }

  @Test
  void rejectsRegisteringTheSameInterfaceTwice() {
    registry.register(PaymentActivities.class, new FakePayments());

    assertThatThrownBy(() -> registry.register(PaymentActivities.class, new FakePayments()))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
