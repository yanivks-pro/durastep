package io.github.yanivks_pro.durastep.activity;

@FunctionalInterface
public interface Heartbeat {

  Heartbeat NONE = () -> true;

  boolean beat();
}
