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
 *
 * <p>Durastep provides the implementation. This interface is not meant to be implemented by
 * applications, and it may gain methods in minor releases.
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

  /**
   * Tells the engine that this attempt is still making progress. Long activities whose {@link
   * ActivityOptions#heartbeatTimeout()} is set call it regularly, more often than that timeout.
   * Calls are cheap: at most one in every third of the timeout reaches the database. Without a
   * heartbeat timeout, it does nothing.
   *
   * @throws ActivityLeaseLostException if another engine has taken this attempt over, in which case
   *     the activity should stop, because its result will be discarded
   */
  void heartbeat();
}
