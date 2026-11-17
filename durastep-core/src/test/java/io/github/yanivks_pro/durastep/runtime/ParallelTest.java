package io.github.yanivks_pro.durastep.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.yanivks_pro.durastep.api.ActivityFailedException;
import io.github.yanivks_pro.durastep.api.ActivityOptions;
import io.github.yanivks_pro.durastep.api.CancelledException;
import io.github.yanivks_pro.durastep.api.NonDeterminismException;
import io.github.yanivks_pro.durastep.api.Promise;
import io.github.yanivks_pro.durastep.payload.JsonPayloadConverter;
import io.github.yanivks_pro.durastep.runtime.Command.CancelWorkflow;
import io.github.yanivks_pro.durastep.runtime.Command.CompleteWorkflow;
import io.github.yanivks_pro.durastep.runtime.Command.ScheduleActivity;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ParallelTest {

  interface Trips {
    String bookFlight(String trip);

    String bookHotel(String trip);

    String confirm(String booking);

    void cancelFlight(String trip);

    void cancelHotel(String trip);
  }

  private static final String FLIGHT = "0.1";
  private static final String HOTEL = "0.2";

  private final JsonPayloadConverter json = new JsonPayloadConverter();
  private final WorkflowRuntime runtime = new WorkflowRuntime(json);

  private static final WorkflowDefinition<String, String> BOTH =
      new WorkflowDefinition<>(
          "Both",
          String.class,
          () ->
              (ctx, trip) -> {
                Trips trips = ctx.activities(Trips.class);
                Promise<String> flight = ctx.async(() -> trips.bookFlight(trip));
                Promise<String> hotel = ctx.async(() -> trips.bookHotel(trip));
                return flight.get() + "+" + hotel.get();
              });

  @Test
  void theFirstDecisionSchedulesTheActivitiesOfBothBranchesTogether() {
    assertThat(decide(BOTH, History.startedWith("trip-1")))
        .containsExactly(
            schedule("Trips.bookFlight", FLIGHT, "trip-1"),
            schedule("Trips.bookHotel", HOTEL, "trip-1"));
  }

  @Test
  void theWorkflowWaitsUntilBothBranchesHaveFinished() {
    History hotelDone = bothScheduled().completed(3, "H");

    assertThat(decide(BOTH, hotelDone)).isEmpty();
    assertThat(decide(BOTH, hotelDone.completed(2, "F")))
        .containsExactly(new CompleteWorkflow(json.toPayload("F+H")));
  }

  @Test
  void theResultIsTheSameWhicheverBranchFinishesFirst() {
    assertThat(decide(BOTH, bothScheduled().completed(2, "F").completed(3, "H")))
        .isEqualTo(decide(BOTH, bothScheduled().completed(3, "H").completed(2, "F")));
  }

  @Test
  void eachBranchNumbersItsOwnSteps() {
    WorkflowDefinition<String, String> twoStepFlight =
        new WorkflowDefinition<>(
            "TwoStepFlight",
            String.class,
            () ->
                (ctx, trip) -> {
                  Trips trips = ctx.activities(Trips.class);
                  Promise<String> flight = ctx.async(() -> trips.confirm(trips.bookFlight(trip)));
                  Promise<String> hotel = ctx.async(() -> trips.bookHotel(trip));
                  return flight.get() + "+" + hotel.get();
                });
    History bothDone = bothScheduled().completed(3, "H").completed(2, "F");

    assertThat(decide(twoStepFlight, bothDone))
        .containsExactly(schedule("Trips.confirm", FLIGHT, "F"));
    assertThat(
            decide(
                twoStepFlight,
                bothDone.scheduledIn(FLIGHT, "Trips.confirm", "F").completed(6, "F confirmed")))
        .containsExactly(new CompleteWorkflow(json.toPayload("F confirmed+H")));
  }

  @Test
  void branchesSeeResultsInTheOrderTheHistoryRecordsThem() {
    WorkflowDefinition<String, String> recordsOrder =
        new WorkflowDefinition<>(
            "RecordsOrder",
            String.class,
            () ->
                (ctx, trip) -> {
                  Trips trips = ctx.activities(Trips.class);
                  List<String> order = new ArrayList<>();
                  Promise<Void> flight =
                      ctx.async(
                          () -> {
                            trips.bookFlight(trip);
                            order.add("flight");
                          });
                  Promise<Void> hotel =
                      ctx.async(
                          () -> {
                            trips.bookHotel(trip);
                            order.add("hotel");
                          });
                  flight.get();
                  hotel.get();
                  return String.join(",", order);
                });

    assertThat(decide(recordsOrder, bothScheduled().completed(3, "H").completed(2, "F")))
        .containsExactly(new CompleteWorkflow(json.toPayload("hotel,flight")));
    assertThat(decide(recordsOrder, bothScheduled().completed(2, "F").completed(3, "H")))
        .containsExactly(new CompleteWorkflow(json.toPayload("flight,hotel")));
  }

  @Test
  void aBranchsFailureReachesTheCodeThatWaitsForIt() {
    WorkflowDefinition<String, String> fallsBack =
        new WorkflowDefinition<>(
            "FallsBack",
            String.class,
            () ->
                (ctx, trip) -> {
                  Trips trips = ctx.activities(Trips.class);
                  Promise<String> flight = ctx.async(() -> trips.bookFlight(trip));
                  Promise<String> hotel = ctx.async(() -> trips.bookHotel(trip));
                  try {
                    return flight.get() + "+" + hotel.get();
                  } catch (ActivityFailedException e) {
                    return flight.get() + " without hotel";
                  }
                });
    History hotelFailed =
        bothScheduled().failed(3, "com.example.Full", "no rooms").completed(2, "F");

    assertThat(decide(fallsBack, hotelFailed))
        .containsExactly(new CompleteWorkflow(json.toPayload("F without hotel")));
  }

  @Test
  void aCancellationReachesEveryBranchAtItsOwnWaitPoint() {
    WorkflowDefinition<String, String> undoes =
        new WorkflowDefinition<>(
            "Undoes",
            String.class,
            () ->
                (ctx, trip) -> {
                  Trips trips = ctx.activities(Trips.class);
                  Promise<String> flight =
                      ctx.async(
                          () -> {
                            try {
                              return trips.bookFlight(trip);
                            } catch (CancelledException e) {
                              trips.cancelFlight(trip);
                              throw e;
                            }
                          });
                  Promise<String> hotel =
                      ctx.async(
                          () -> {
                            try {
                              return trips.bookHotel(trip);
                            } catch (CancelledException e) {
                              trips.cancelHotel(trip);
                              throw e;
                            }
                          });
                  return flight.get() + "+" + hotel.get();
                });
    History cancelled = bothScheduled().cancelRequested();

    assertThat(decide(undoes, cancelled))
        .containsExactly(
            schedule("Trips.cancelFlight", FLIGHT, "trip-1"),
            schedule("Trips.cancelHotel", HOTEL, "trip-1"));
    History undone =
        cancelled
            .scheduledIn(FLIGHT, "Trips.cancelFlight", "trip-1")
            .scheduledIn(HOTEL, "Trips.cancelHotel", "trip-1")
            .completed(6, null)
            .completed(5, null);
    assertThat(decide(undoes, undone)).containsExactly(new CancelWorkflow());
  }

  @Test
  void codeThatReceivesACancellationFromABranchCleansUpWithoutBeingCancelledAgain() {
    WorkflowDefinition<String, String> cleansUpAfterABranch =
        new WorkflowDefinition<>(
            "CleansUpAfterABranch",
            String.class,
            () ->
                (ctx, trip) -> {
                  Trips trips = ctx.activities(Trips.class);
                  try {
                    return ctx.async(() -> trips.bookFlight(trip)).get();
                  } catch (CancelledException e) {
                    trips.cancelFlight(trip);
                    trips.cancelHotel(trip);
                    throw e;
                  }
                });
    History cancelled =
        History.startedWith("trip-1")
            .scheduledIn(FLIGHT, "Trips.bookFlight", "trip-1")
            .cancelRequested();

    assertThat(decide(cleansUpAfterABranch, cancelled))
        .containsExactly(schedule("Trips.cancelFlight", HistoryEvent.MAIN_BRANCH, "trip-1"));
  }

  @Test
  void aBranchStartedDuringCleanupIsNotCancelledAgain() {
    WorkflowDefinition<String, String> cleansUpInABranch =
        new WorkflowDefinition<>(
            "CleansUpInABranch",
            String.class,
            () ->
                (ctx, trip) -> {
                  Trips trips = ctx.activities(Trips.class);
                  try {
                    return trips.bookFlight(trip);
                  } catch (CancelledException e) {
                    ctx.async(() -> trips.cancelFlight(trip)).get();
                    throw e;
                  }
                });
    History cancelled =
        History.startedWith("trip-1").scheduled("Trips.bookFlight", "trip-1").cancelRequested();

    assertThat(decide(cleansUpInABranch, cancelled))
        .containsExactly(schedule("Trips.cancelFlight", FLIGHT, "trip-1"));
  }

  @Test
  void whenTheMainCodeFinishesFirstTheWorkflowCompletesAndOpenBranchesAreDropped() {
    WorkflowDefinition<String, String> doesNotWait =
        new WorkflowDefinition<>(
            "DoesNotWait",
            String.class,
            () ->
                (ctx, trip) -> {
                  Trips trips = ctx.activities(Trips.class);
                  ctx.async(() -> trips.bookFlight(trip));
                  return "started";
                });

    assertThat(decide(doesNotWait, History.startedWith("trip-1")))
        .containsExactly(new CompleteWorkflow(json.toPayload("started")));
  }

  @Test
  void onlyTheMainCodeMayWaitForSignals() {
    WorkflowDefinition<String, String> waitsInABranch =
        new WorkflowDefinition<>(
            "WaitsInABranch",
            String.class,
            () -> (ctx, trip) -> ctx.async(() -> ctx.awaitSignal("go", String.class)).get());

    assertThatThrownBy(() -> decide(waitsInABranch, History.startedWith("trip-1")))
        .isInstanceOf(WorkflowBlockedException.class)
        .rootCause()
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("only be called from the workflow's main code");
  }

  @Test
  void eachBranchHasItsOwnClockAndRandomNumbers() {
    WorkflowDefinition<String, String> reportsTimeAndRandom =
        new WorkflowDefinition<>(
            "ReportsTimeAndRandom",
            String.class,
            () ->
                (ctx, trip) -> {
                  Trips trips = ctx.activities(Trips.class);
                  int mainRandom = ctx.random().nextInt();
                  Promise<Instant> flight =
                      ctx.async(
                          () -> {
                            trips.bookFlight(trip);
                            return ctx.now();
                          });
                  int branchRandom = ctx.async(() -> ctx.random().nextInt()).get();
                  Instant flightTime = flight.get();
                  return mainRandom
                      + " "
                      + (branchRandom != mainRandom)
                      + " "
                      + flightTime
                      + " "
                      + ctx.now();
                });
    WorkflowDefinition<String, String> noBranches =
        new WorkflowDefinition<>(
            "NoBranches", String.class, () -> (ctx, trip) -> "" + ctx.random().nextInt());
    History flightBooked =
        History.startedWith("trip-1")
            .scheduledIn(FLIGHT, "Trips.bookFlight", "trip-1")
            .completed(2, "F");

    CompleteWorkflow plain =
        (CompleteWorkflow) decide(noBranches, History.startedWith("trip-1")).getFirst();
    assertThat(decide(reportsTimeAndRandom, flightBooked))
        .containsExactly(
            new CompleteWorkflow(
                json.toPayload(
                    json.fromPayload(plain.result(), String.class)
                        + " true "
                        + flightBooked.timeOf(3)
                        + " "
                        + History.START)));
  }

  @Test
  void aBranchCallingADifferentStepThanItsHistoryIsANonDeterminismError() {
    History hotelInTheFlightBranch =
        History.startedWith("trip-1").scheduledIn(FLIGHT, "Trips.bookHotel", "trip-1");

    assertThatThrownBy(() -> decide(BOTH, hotelInTheFlightBranch))
        .isInstanceOf(NonDeterminismException.class)
        .hasMessageContaining("called Trips.bookFlight at step 1 of branch 0.1")
        .hasMessageContaining("Trips.bookHotel");
  }

  @Test
  void finishingWithoutStartingABranchFromTheHistoryIsANonDeterminismError() {
    WorkflowDefinition<String, String> noLongerBranches =
        new WorkflowDefinition<>("NoLongerBranches", String.class, () -> (ctx, trip) -> "done");

    assertThatThrownBy(() -> decide(noLongerBranches, bothScheduled()))
        .isInstanceOf(NonDeterminismException.class)
        .hasMessageContaining("without starting branch 0.1");
  }

  private static History bothScheduled() {
    return History.startedWith("trip-1")
        .scheduledIn(FLIGHT, "Trips.bookFlight", "trip-1")
        .scheduledIn(HOTEL, "Trips.bookHotel", "trip-1");
  }

  private List<Command> decide(WorkflowDefinition<String, String> workflow, History history) {
    return runtime.decide(workflow, "trip-1", history.entries());
  }

  private ScheduleActivity schedule(String activity, String branch, Object... arguments) {
    return new ScheduleActivity(
        activity, json.toArguments(arguments), ActivityOptions.DEFAULT, branch);
  }
}
