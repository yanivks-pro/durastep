package io.github.yanivks_pro.durastep.examples.expense;

public record ExpenseClaim(
    String id,
    String employee,
    long cents,
    String reason,
    long managerWaitSeconds,
    long directorWaitSeconds) {}
