package io.github.yanivks_pro.durastep.examples.order;

public interface Mail {

  void sendConfirmation(String customer, String orderId, String trackingNumber);
}
