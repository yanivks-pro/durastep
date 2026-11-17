package io.github.yanivks_pro.durastep.payload;

import io.github.yanivks_pro.durastep.api.PayloadTooLargeException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;

public final class LimitedPayloadConverter implements PayloadConverter {

  public static final int DEFAULT_LIMIT = 256 * 1_024;

  private final PayloadConverter converter;
  private final int limit;

  public LimitedPayloadConverter(PayloadConverter converter, int limit) {
    if (limit < 1) {
      throw new IllegalArgumentException("the payload limit must be positive, was " + limit);
    }
    this.converter = converter;
    this.limit = limit;
  }

  @Override
  public Payload toPayload(Object value) {
    return checked(converter.toPayload(value));
  }

  @Override
  public Object fromPayload(Payload payload, Type type) {
    return converter.fromPayload(payload, type);
  }

  @Override
  public Payload toArguments(Object[] arguments) {
    return checked(converter.toArguments(arguments));
  }

  @Override
  public Object[] fromArguments(Payload arguments, Type[] types) {
    return converter.fromArguments(arguments, types);
  }

  private Payload checked(Payload payload) {
    int size = payload.json().getBytes(StandardCharsets.UTF_8).length;
    if (size > limit) {
      throw new PayloadTooLargeException(size, limit);
    }
    return payload;
  }
}
