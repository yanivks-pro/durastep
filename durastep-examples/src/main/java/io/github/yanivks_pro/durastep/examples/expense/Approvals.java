package io.github.yanivks_pro.durastep.examples.expense;

public interface Approvals {

  void askManager(ExpenseClaim claim);

  void askDirector(ExpenseClaim claim);

  void pay(ExpenseClaim claim);

  void tellEmployee(ExpenseClaim claim, String message);
}
