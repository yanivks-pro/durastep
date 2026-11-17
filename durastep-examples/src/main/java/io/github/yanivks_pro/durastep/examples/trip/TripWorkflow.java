package io.github.yanivks_pro.durastep.examples.trip;

import io.github.yanivks_pro.durastep.api.ActivityFailedException;
import io.github.yanivks_pro.durastep.api.ActivityOptions;
import io.github.yanivks_pro.durastep.api.CancelledException;
import io.github.yanivks_pro.durastep.api.Promise;
import io.github.yanivks_pro.durastep.api.RetryPolicy;
import io.github.yanivks_pro.durastep.api.Saga;
import io.github.yanivks_pro.durastep.api.Workflow;
import io.github.yanivks_pro.durastep.api.WorkflowContext;
import java.time.Duration;

public final class TripWorkflow implements Workflow<Trip, String> {

  private static final ActivityOptions SOLD_OUT_IS_FINAL =
      ActivityOptions.builder()
          .retryPolicy(
              RetryPolicy.builder()
                  .initialBackoff(Duration.ofMillis(200))
                  .nonRetryable(SoldOutException.class)
                  .build())
          .build();

  @Override
  public String run(WorkflowContext ctx, Trip trip) {
    Bookings bookings = ctx.activities(Bookings.class, SOLD_OUT_IS_FINAL);
    Saga saga = ctx.saga();
    try {
      ctx.setStatus("booking the flight and the hotel");
      saga.addCompensation(() -> bookings.cancelFlight(trip.id()));
      saga.addCompensation(() -> bookings.cancelHotel(trip.id()));
      Promise<String> flight = ctx.async(() -> bookings.bookFlight(trip));
      Promise<String> hotel = ctx.async(() -> bookings.bookHotel(trip));
      Promise.allOf(flight, hotel).get();

      ctx.setStatus("booking a car");
      saga.addCompensation(() -> bookings.cancelCar(trip.id()));
      String car = bookings.bookCar(trip);

      ctx.setStatus("booked");
      return flight.get() + ", " + hotel.get() + ", " + car;
    } catch (ActivityFailedException | CancelledException e) {
      ctx.setStatus("undoing the bookings");
      saga.compensate();
      ctx.setStatus(
          e instanceof CancelledException ? "cancelled, nothing booked" : "failed, nothing booked");
      throw e;
    }
  }
}
