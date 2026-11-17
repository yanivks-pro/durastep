package io.github.yanivks_pro.durastep.examples.order;

public interface Payments {

  String charge(String orderId, long cents);
}
