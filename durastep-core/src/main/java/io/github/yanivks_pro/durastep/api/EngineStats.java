package io.github.yanivks_pro.durastep.api;

import java.time.Duration;
import java.util.Map;

/**
 * A snapshot of all workflows and pending work in the database, as {@link DurastepClient#stats()}
 * returns it.
 *
 * @param running how many workflows are running, including blocked ones
 * @param blocked how many running workflows are blocked by an error
 * @param readyDecisions how many decisions are waiting for an engine
 * @param readyActivities how many activities are waiting for an engine, by queue
 * @param oldestReadyWork how long the oldest waiting decision or activity has waited, or zero
 */
public record EngineStats(
    long running,
    long blocked,
    long readyDecisions,
    Map<String, Long> readyActivities,
    Duration oldestReadyWork) {

  /** Copies the map so the snapshot cannot change. */
  public EngineStats {
    readyActivities = Map.copyOf(readyActivities);
  }
}
