package io.github.yanivks_pro.durastep.api;

import io.github.yanivks_pro.durastep.activity.ActivityScope;

/**
 * Information about the activity that is currently running, available only inside activity code.
 *
 * <p>Activities run <em>at least once</em>: after a crash, an activity may run again. Pass {@link
 * #idempotencyKey()} to external systems so they can recognise and ignore the repeat.
 *
 * <pre>{@code
 * public String chargeCard(String orderId, long cents) {
 *   return paymentApi.charge(orderId, cents, ActivityContext.current().idempotencyKey());
 * }
 * }</pre>
 */
public interface ActivityContext {

  /**
   * Returns the context of the activity running on the current thread.
   *
   * @return the current activity's context
   * @throws IllegalStateException if called outside an activity
   */
  static ActivityContext current() {
    return ActivityScope.current();
  }

  /**
   * Returns the ID of the workflow this activity belongs to.
   *
   * @return the workflow ID, for example {@code order-42}
   */
  String workflowId();

  /**
   * Returns a key that identifies this activity call and stays the same every time it runs,
   * including after a crash.
   *
   * @return the idempotency key, for example {@code order-42/activity-2}
   */
  String idempotencyKey();
}
