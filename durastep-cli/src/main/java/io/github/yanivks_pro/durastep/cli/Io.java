package io.github.yanivks_pro.durastep.cli;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.UncheckedIOException;
import java.util.Map;
import java.util.Optional;

record Io(
    BufferedReader in,
    PrintWriter out,
    PrintWriter err,
    Map<String, String> environment,
    boolean interactive) {

  Optional<String> env(String name) {
    return Optional.ofNullable(environment.get(name)).filter(value -> !value.isBlank());
  }

  String readLine() {
    try {
      String line = in.readLine();
      return line == null ? "" : line;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
