package io.github.yanivks_pro.durastep.payload;

import io.github.yanivks_pro.durastep.api.PayloadCodec;
import java.lang.reflect.Type;

public final class EncryptingPayloadConverter implements PayloadConverter {

  private final PayloadConverter converter;
  private final PayloadCodec codec;

  public EncryptingPayloadConverter(PayloadConverter converter, PayloadCodec codec) {
    this.converter = converter;
    this.codec = codec;
  }

  @Override
  public Payload toPayload(Object value) {
    return new Payload(codec.encode(converter.toPayload(value).json()));
  }

  @Override
  public Object fromPayload(Payload payload, Type type) {
    return converter.fromPayload(decoded(payload), type);
  }

  @Override
  public Payload toArguments(Object[] arguments) {
    return new Payload(codec.encode(converter.toArguments(arguments).json()));
  }

  @Override
  public Object[] fromArguments(Payload arguments, Type[] types) {
    return converter.fromArguments(decoded(arguments), types);
  }

  public Payload decoded(Payload payload) {
    return new Payload(codec.decode(payload.json()));
  }
}
