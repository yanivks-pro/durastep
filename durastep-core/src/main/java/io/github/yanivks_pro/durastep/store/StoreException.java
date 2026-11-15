package io.github.yanivks_pro.durastep.store;

import java.sql.SQLException;

public final class StoreException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  StoreException(String message, SQLException cause) {
    super(message, cause);
  }
}
