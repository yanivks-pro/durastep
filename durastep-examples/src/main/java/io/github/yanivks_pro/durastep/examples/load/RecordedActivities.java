package io.github.yanivks_pro.durastep.examples.load;

import io.github.yanivks_pro.durastep.examples.expense.Approvals;
import io.github.yanivks_pro.durastep.examples.expense.ExpenseClaim;
import io.github.yanivks_pro.durastep.examples.order.Payments;
import io.github.yanivks_pro.durastep.examples.trip.Bookings;
import io.github.yanivks_pro.durastep.examples.trip.Trip;

final class RecordedActivities {

  private RecordedActivities() {}

  static Payments payments(Ledger ledger) {
    Payments flaky = new LoadGenerator.FlakyPayments();
    return (orderId, cents) -> {
      String charge = flaky.charge(orderId, cents);
      ledger.record("charge");
      return charge;
    };
  }

  static Bookings bookings(Ledger ledger) {
    Bookings busy = new LoadGenerator.BusyBookings();
    return new Bookings() {
      @Override
      public String bookFlight(Trip trip) {
        String flight = busy.bookFlight(trip);
        ledger.record("book flight");
        return flight;
      }

      @Override
      public String bookHotel(Trip trip) {
        String hotel = busy.bookHotel(trip);
        ledger.record("book hotel");
        return hotel;
      }

      @Override
      public String bookCar(Trip trip) {
        String car = busy.bookCar(trip);
        ledger.record("book car");
        return car;
      }

      @Override
      public void cancelFlight(String tripId) {
        busy.cancelFlight(tripId);
        ledger.record("cancel flight");
      }

      @Override
      public void cancelHotel(String tripId) {
        busy.cancelHotel(tripId);
        ledger.record("cancel hotel");
      }

      @Override
      public void cancelCar(String tripId) {
        busy.cancelCar(tripId);
        ledger.record("cancel car");
      }
    };
  }

  static Approvals approvals(Ledger ledger) {
    Approvals quiet = new LoadGenerator.QuietApprovals();
    return new Approvals() {
      @Override
      public void askManager(ExpenseClaim claim) {
        quiet.askManager(claim);
      }

      @Override
      public void askDirector(ExpenseClaim claim) {
        quiet.askDirector(claim);
      }

      @Override
      public void pay(ExpenseClaim claim) {
        quiet.pay(claim);
        ledger.record("pay");
      }

      @Override
      public void tellEmployee(ExpenseClaim claim, String message) {
        quiet.tellEmployee(claim, message);
      }
    };
  }
}
