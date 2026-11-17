package io.github.yanivks_pro.durastep.examples.order;

import io.github.yanivks_pro.durastep.api.ActivityOptions;
import io.github.yanivks_pro.durastep.api.RetryPolicy;
import io.github.yanivks_pro.durastep.api.Workflow;
import io.github.yanivks_pro.durastep.api.WorkflowContext;
import java.time.Duration;

public final class OrderWorkflow implements Workflow<Order, String> {

  private static final ActivityOptions RETRY_QUICKLY =
      ActivityOptions.builder()
          .retryPolicy(
              RetryPolicy.builder()
                  .maxAttempts(5)
                  .initialBackoff(Duration.ofMillis(200))
                  .maxBackoff(Duration.ofSeconds(2))
                  .build())
          .build();

  @Override
  public String run(WorkflowContext ctx, Order order) {
    Payments payments = ctx.activities(Payments.class, RETRY_QUICKLY);
    Warehouse warehouse = ctx.activities(Warehouse.class, RETRY_QUICKLY);
    Mail mail = ctx.activities(Mail.class, RETRY_QUICKLY);

    ctx.setStatus("charging the card");
    payments.charge(order.id(), order.cents());
    ctx.setStatus("reserving the " + order.item());
    warehouse.reserve(order.id(), order.item());
    ctx.setStatus("shipping");
    String trackingNumber = warehouse.ship(order.id(), order.customer());
    ctx.setStatus("sending the confirmation");
    mail.sendConfirmation(order.customer(), order.id(), trackingNumber);
    ctx.setStatus("done, tracking number " + trackingNumber);
    return trackingNumber;
  }
}
