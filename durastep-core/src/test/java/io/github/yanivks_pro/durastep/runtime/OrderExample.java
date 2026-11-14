package io.github.yanivks_pro.durastep.runtime;

import io.github.yanivks_pro.durastep.api.Workflow;
import io.github.yanivks_pro.durastep.api.WorkflowContext;
import java.time.Instant;

final class OrderExample {

  private OrderExample() {}

  record Order(String id, long cents) {}

  record Receipt(String chargeId, Instant confirmedAt) {}

  interface OrderActivities {
    String chargeCard(String orderId, long cents);

    void reserveStock(String orderId);

    void sendEmail(String orderId);
  }

  static final class OrderWorkflow implements Workflow<Order, Receipt> {
    @Override
    public Receipt run(WorkflowContext ctx, Order order) {
      OrderActivities activities = ctx.activities(OrderActivities.class);
      String chargeId = activities.chargeCard(order.id(), order.cents());
      activities.reserveStock(order.id());
      activities.sendEmail(order.id());
      return new Receipt(chargeId, ctx.now());
    }
  }

  static WorkflowDefinition<Order, Receipt> definition() {
    return new WorkflowDefinition<>("OrderWorkflow", Order.class, OrderWorkflow::new);
  }
}
