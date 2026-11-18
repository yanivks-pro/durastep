package io.github.yanivks_pro.durastep.benchmarks;

record Percentiles(long count, double p50, double p90, double p99, double max) {

  String row(String name) {
    return "| %s | %,d | %.1f | %.1f | %.1f | %.1f |".formatted(name, count, p50, p90, p99, max);
  }
}
