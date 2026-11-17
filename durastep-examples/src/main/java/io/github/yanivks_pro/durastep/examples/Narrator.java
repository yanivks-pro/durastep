package io.github.yanivks_pro.durastep.examples;

import java.io.PrintStream;
import java.util.Locale;

public final class Narrator {

  private final PrintStream out;
  private final long startedAt = System.nanoTime();

  public Narrator(PrintStream out) {
    this.out = out;
  }

  public synchronized void say(String who, String what) {
    double seconds = (System.nanoTime() - startedAt) / 1e9;
    out.println(String.format(Locale.ROOT, "%6.2fs  %-11s %s", seconds, who, what));
  }
}
