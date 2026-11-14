package io.github.yanivks_pro.durastep.payload;

import java.lang.reflect.Type;
import tools.jackson.databind.json.JsonMapper;

public final class JsonPayloadConverter implements PayloadConverter {

  private final JsonMapper mapper;

  public JsonPayloadConverter() {
    this(JsonMapper.builder().build());
  }

  public JsonPayloadConverter(JsonMapper mapper) {
    this.mapper = mapper;
  }

  @Override
  public Payload toPayload(Object value) {
    return new Payload(mapper.writeValueAsString(value));
  }

  @Override
  public Object fromPayload(Payload payload, Type type) {
    return mapper.readValue(payload.json(), mapper.constructType(type));
  }
}
