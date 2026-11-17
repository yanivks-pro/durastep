package io.github.yanivks_pro.durastep.examples.order;

public interface Warehouse {

  void reserve(String orderId, String item);

  String ship(String orderId, String customer);
}
