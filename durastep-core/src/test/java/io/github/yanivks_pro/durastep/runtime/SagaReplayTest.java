package io.github.yanivks_pro.durastep.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.yanivks_pro.durastep.api.ActivityFailedException;
import io.github.yanivks_pro.durastep.api.ActivityOptions;
import io.github.yanivks_pro.durastep.api.Saga;
import io.github.yanivks_pro.durastep.payload.JsonPayloadConverter;
import io.github.yanivks_pro.durastep.payload.Payload;
import io.github.yanivks_pro.durastep.runtime.Command.FailWorkflow;
import io.github.yanivks_pro.durastep.runtime.Command.ScheduleActivity;
import java.util.List;
import org.junit.jupiter.api.Test;

class SagaReplayTest {

  interface Trips {
    String bookFlight(String trip);

    String bookHotel(String trip);

    void bookCar(String trip);

    void cancelFlight(String flight);

    void cancelHotel(String hotel);
  }

  private static final WorkflowDefinition<String, String> TRIP =
      new WorkflowDefinition<>(
          "Trip",
          String.class,
          () ->
              (ctx, trip) -> {
                Trips trips = ctx.activities(Trips.class);
                Saga saga = ctx.saga();
                String flight = trips.bookFlight(trip);
                saga.addCompensation(() -> trips.cancelFlight(flight));
                String hotel = trips.bookHotel(trip);
                saga.addCompensation(() -> trips.cancelHotel(hotel));
                try {
                  trips.bookCar(trip);
                } catch (ActivityFailedException e) {
                  saga.compensate();
                  throw e;
                }
                return flight + "+" + hotel;
              });

  private final WorkflowRuntime runtime = new WorkflowRuntime(new JsonPayloadConverter());

  @Test
  void whenTheCarFailsTheHotelIsCancelledFirst() {
    assertThat(decide(bookedFlightAndHotelButNoCar()))
        .containsExactly(schedule("Trips.cancelHotel", "[\"H-2\"]"));
  }

  @Test
  void afterTheHotelIsCancelledTheFlightIsCancelledNext() {
    History history =
        bookedFlightAndHotelButNoCar().scheduled("Trips.cancelHotel", "H-2").completed(8, null);

    assertThat(decide(history)).containsExactly(schedule("Trips.cancelFlight", "[\"F-1\"]"));
  }

  @Test
  void onceEverythingIsUndoneTheWorkflowFailsWithTheOriginalError() {
    History history =
        bookedFlightAndHotelButNoCar()
            .scheduled("Trips.cancelHotel", "H-2")
            .completed(8, null)
            .scheduled("Trips.cancelFlight", "F-1")
            .completed(10, null);

    assertThat(decide(history))
        .singleElement()
        .isInstanceOfSatisfying(
            FailWorkflow.class,
            fail -> assertThat(fail.failure().message()).contains("Trips.bookCar", "no cars left"));
  }

  private static History bookedFlightAndHotelButNoCar() {
    return History.startedWith("trip-1")
        .scheduled("Trips.bookFlight", "trip-1")
        .completed(2, "F-1")
        .scheduled("Trips.bookHotel", "trip-1")
        .completed(4, "H-2")
        .scheduled("Trips.bookCar", "trip-1")
        .failed(6, "com.example.NoCarsLeft", "no cars left");
  }

  private List<Command> decide(History history) {
    return runtime.decide(TRIP, "trip-1", history.entries());
  }

  private static ScheduleActivity schedule(String activity, String arguments) {
    return new ScheduleActivity(activity, new Payload(arguments), ActivityOptions.DEFAULT);
  }
}
