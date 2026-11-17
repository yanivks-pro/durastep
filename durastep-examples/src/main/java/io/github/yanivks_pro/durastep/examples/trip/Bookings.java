package io.github.yanivks_pro.durastep.examples.trip;

public interface Bookings {

  String bookFlight(Trip trip);

  String bookHotel(Trip trip);

  String bookCar(Trip trip);

  void cancelFlight(String tripId);

  void cancelHotel(String tripId);

  void cancelCar(String tripId);
}
