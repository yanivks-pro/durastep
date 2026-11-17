package io.github.yanivks_pro.durastep.examples.trip;

import io.github.yanivks_pro.durastep.api.DurastepEngine;
import io.github.yanivks_pro.durastep.api.WorkflowCancelledException;
import io.github.yanivks_pro.durastep.api.WorkflowFailedException;
import io.github.yanivks_pro.durastep.api.WorkflowHandle;
import io.github.yanivks_pro.durastep.api.WorkflowState;
import io.github.yanivks_pro.durastep.examples.Narrator;
import io.github.yanivks_pro.durastep.examples.StatusWatcher;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import javax.sql.DataSource;

public final class TripExample {

  public enum Scenario {
    HAPPY,
    NO_CARS,
    CUSTOMER_CANCELS
  }

  public record Result(
      WorkflowState state, String outcome, Duration took, Set<String> stillBooked) {}

  private static final Duration BOOKING_TIME = Duration.ofSeconds(1);

  private TripExample() {}

  public static Result run(DataSource dataSource, Scenario scenario, Narrator narrator)
      throws Exception {
    FakeBookings bookings =
        new FakeBookings(
            narrator,
            scenario == Scenario.NO_CARS,
            scenario == Scenario.CUSTOMER_CANCELS ? BOOKING_TIME.multipliedBy(3) : BOOKING_TIME);
    try (DurastepEngine engine =
        DurastepEngine.builder()
            .dataSource(dataSource)
            .workflow(TripWorkflow.class, TripWorkflow::new)
            .activities(Bookings.class, bookings)
            .build()) {
      engine.start();
      Trip trip = new Trip("trip-" + UUID.randomUUID().toString().substring(0, 8), "Ada", "Lisbon");
      narrator.say("traveller", trip.traveller() + " books a trip to " + trip.destination());
      long starting = System.nanoTime();
      WorkflowHandle<String> workflow = engine.client().start(TripWorkflow.class, trip.id(), trip);
      StatusWatcher watcher = StatusWatcher.start(workflow, narrator);
      String outcome;
      try {
        if (scenario == Scenario.CUSTOMER_CANCELS) {
          StatusWatcher.awaitStatus(workflow, "booking a car", Duration.ofSeconds(20));
          narrator.say("traveller", "changes their mind and cancels the trip");
          engine.client().cancel(trip.id());
        }
        outcome = "booked: " + workflow.result(Duration.ofSeconds(30));
      } catch (WorkflowFailedException e) {
        outcome = "failed: " + e.getMessage().substring(e.getMessage().lastIndexOf(": ") + 2);
      } catch (WorkflowCancelledException e) {
        outcome = "cancelled";
      } finally {
        watcher.stop();
      }
      Duration took = Duration.ofNanos(System.nanoTime() - starting);
      narrator.say("traveller", outcome);
      narrator.say("bookings", "still booked: " + bookings.stillBooked());
      return new Result(workflow.describe().state(), outcome, took, bookings.stillBooked());
    }
  }

  static final class FakeBookings implements Bookings {
    private final Narrator narrator;
    private final boolean noCars;
    private final Duration carBookingTime;
    private final Map<String, String> booked = new ConcurrentHashMap<>();
    private final Set<String> cancelled = ConcurrentHashMap.newKeySet();

    FakeBookings(Narrator narrator, boolean noCars, Duration carBookingTime) {
      this.narrator = narrator;
      this.noCars = noCars;
      this.carBookingTime = carBookingTime;
    }

    @Override
    public String bookFlight(Trip trip) {
      return book(
          "airline", "flight", trip, BOOKING_TIME, "flight TP1350 to " + trip.destination());
    }

    @Override
    public String bookHotel(Trip trip) {
      return book("hotel", "hotel", trip, BOOKING_TIME, "3 nights at Hotel Alfama");
    }

    @Override
    public String bookCar(Trip trip) {
      if (noCars) {
        pause(BOOKING_TIME);
        narrator.say("car rental", "no cars left in " + trip.destination());
        throw new SoldOutException("no cars left in " + trip.destination());
      }
      return book("car rental", "car", trip, carBookingTime, "a small car");
    }

    @Override
    public void cancelFlight(String tripId) {
      cancel("airline", "flight", tripId);
    }

    @Override
    public void cancelHotel(String tripId) {
      cancel("hotel", "hotel", tripId);
    }

    @Override
    public void cancelCar(String tripId) {
      cancel("car rental", "car", tripId);
    }

    Set<String> stillBooked() {
      return Set.copyOf(booked.keySet());
    }

    private String book(String who, String kind, Trip trip, Duration takes, String booking) {
      narrator.say(who, "booking the " + kind + "...");
      pause(takes);
      String key = trip.id() + "/" + kind;
      if (cancelled.contains(key)) {
        narrator.say(who, "the " + kind + " was cancelled while it was being booked, released it");
        return booking;
      }
      booked.put(key, booking);
      narrator.say(who, "booked " + booking);
      return booking;
    }

    private void cancel(String who, String kind, String tripId) {
      String key = tripId + "/" + kind;
      cancelled.add(key);
      narrator.say(
          who,
          booked.remove(key) != null
              ? "cancelled the " + kind
              : "nothing to cancel for the " + kind + " (yet)");
    }

    private static void pause(Duration duration) {
      try {
        Thread.sleep(duration);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException(e);
      }
    }
  }
}
