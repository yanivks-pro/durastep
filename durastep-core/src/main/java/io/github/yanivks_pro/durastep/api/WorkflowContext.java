package io.github.yanivks_pro.durastep.api;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * The workflow's view of the engine, passed to {@link Workflow#run}.
 *
 * <p>Every method returns the same values when the workflow is replayed, so workflow code stays
 * deterministic.
 */
public interface WorkflowContext {

  /**
   * Returns a stub for an activity interface, using {@link ActivityOptions#DEFAULT}. Calling a
   * method on the stub runs the activity durably: it executes once its turn comes, and on replay
   * the stub returns the saved result without executing it again. When an activity fails for good,
   * the call throws {@link ActivityFailedException}.
   *
   * @param activityInterface the interface that declares the activities
   * @param <T> the interface type
   * @return a stub implementing {@code activityInterface}
   * @throws IllegalArgumentException if {@code activityInterface} is not an interface
   */
  <T> T activities(Class<T> activityInterface);

  /**
   * Returns a stub for an activity interface whose activities use the given timeouts and retries.
   * When an activity fails for good, the call throws {@link ActivityFailedException}.
   *
   * @param activityInterface the interface that declares the activities
   * @param options the timeouts and retry policy for these activities
   * @param <T> the interface type
   * @return a stub implementing {@code activityInterface}
   * @throws IllegalArgumentException if {@code activityInterface} is not an interface
   */
  <T> T activities(Class<T> activityInterface, ActivityOptions options);

  /**
   * Pauses the workflow durably. The wake-up time is stored in the database, so the sleep survives
   * restarts and may last days; any engine continues the workflow when the time has come, about a
   * second after it. On replay, a sleep that has already ended returns at once.
   *
   * <p>After waking up, {@link #now()} returns the time the timer fired.
   *
   * @param duration how long to sleep; zero wakes up at the next check
   * @throws IllegalArgumentException if {@code duration} is negative
   */
  void sleep(Duration duration);

  /**
   * Waits for a signal sent with {@link DurastepClient#signal(String, String, Object)}, for as long
   * as it takes. Signals that arrived before the workflow waited are kept, and each call takes the
   * oldest one with this name that no earlier call has taken, so every signal is received exactly
   * once.
   *
   * @param name the signal's name
   * @param type the type of the signal's payload
   * @param <T> the payload type
   * @return the signal's payload
   */
  <T> T awaitSignal(String name, Class<T> type);

  /**
   * Waits for a signal, but at most {@code timeout}. The timeout is a durable timer, like {@link
   * #sleep}.
   *
   * @param name the signal's name
   * @param type the type of the signal's payload
   * @param timeout how long to wait
   * @param <T> the payload type
   * @return the signal's payload, or empty if the timeout passed first
   * @throws IllegalArgumentException if {@code timeout} is negative
   */
  <T> Optional<T> awaitSignal(String name, Class<T> type, Duration timeout);

  /**
   * Publishes a short, human-readable status, such as {@code "waiting for approval"}. The latest
   * value is visible through {@link WorkflowHandle#describe()}. It is not part of the history.
   *
   * @param status at most 1,000 characters
   * @throws IllegalArgumentException if {@code status} is longer than that
   */
  void setStatus(String status);

  /**
   * Starts a branch: workflow code that runs alongside the code that started it, so that its
   * activities run at the same time as others. Branches take turns on one logical thread; each runs
   * until it waits for something, such as an activity, a timer or another branch.
   *
   * <p>Each branch has its own steps, its own {@link #now()} starting at the time it was started,
   * and its own {@link #random()}. A cancellation reaches every branch at its next wait point. Only
   * the main workflow code may wait for signals. When the main code finishes, the workflow ends,
   * and branches that are still waiting are dropped.
   *
   * @param body the branch's code
   * @param <T> the type of the branch's result
   * @return a promise of the branch's result
   */
  <T> Promise<T> async(Supplier<T> body);

  /**
   * Starts a branch that has no result. See {@link #async(Supplier)}.
   *
   * @param body the branch's code
   * @return a promise that is done when the branch has finished
   */
  Promise<Void> async(Runnable body);

  /**
   * Lets running workflows keep their old code path after a deploy changes it, while workflows that
   * reach this point for the first time take the new one. A workflow that already went past this
   * point before the change gets {@link #DEFAULT_VERSION}; any other gets {@code maxVersion}, which
   * is recorded in its history so that every replay returns the same value.
   *
   * <pre>{@code
   * if (ctx.version("send-sms-instead-of-email", 1) >= 1) {
   *   sms.send(order);
   * } else {
   *   mail.send(order);
   * }
   * }</pre>
   *
   * <p>The recorded version is not a step, so adding a version check does not change the step
   * numbers of running workflows. Keep the old path until no running workflow uses it.
   *
   * @param changeId names the change; the same ID always returns the same version in one workflow
   * @param maxVersion the version of the newest code path, at least 1
   * @return {@link #DEFAULT_VERSION} for a workflow that went past this point before the change, or
   *     the recorded or newest version otherwise
   * @throws IllegalArgumentException if the change ID is blank or {@code maxVersion} is below 1
   */
  int version(String changeId, int maxVersion);

  /**
   * The version {@link #version} returns to workflows that went past a change before it existed.
   */
  int DEFAULT_VERSION = 0;

  /**
   * Returns a new saga for undoing completed steps when a later one fails.
   *
   * @return an empty saga
   */
  default Saga saga() {
    return new Saga();
  }

  /**
   * Returns the workflow's current time: the database time at which the workflow reached its
   * current step. It is the same on every replay.
   *
   * @return the workflow's current time
   */
  Instant now();

  /**
   * Returns a random number generator that produces the same sequence on every replay of this
   * workflow.
   *
   * @return the workflow's random number generator
   */
  Random random();

  /**
   * Returns a random UUID that is the same on every replay of this workflow.
   *
   * @return a version 4 UUID drawn from {@link #random()}
   */
  UUID randomUUID();
}
