package io.github.yanivks_pro.durastep.store;

@FunctionalInterface
public interface CrashPoints {

  CrashPoints NONE = point -> {};

  void reached(CrashPoint point);
}
