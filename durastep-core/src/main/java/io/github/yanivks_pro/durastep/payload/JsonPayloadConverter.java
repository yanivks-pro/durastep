package io.github.yanivks_pro.durastep.payload;

import java.lang.reflect.Type;
import tools.jackson.databind.JsonNode;
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

  @Override
  public Payload toArguments(Object[] arguments) {
    return toPayload(arguments == null ? new Object[0] : arguments);
  }

  @Override
  public Object[] fromArguments(Payload arguments, Type[] types) {
    JsonNode array = mapper.readTree(arguments.json());
    if (!array.isArray() || array.size() != types.length) {
      throw new IllegalArgumentException(
          "expected a JSON array of " + types.length + " arguments, got " + arguments.json());
    }
    Object[] values = new Object[types.length];
    for (int i = 0; i < types.length; i++) {
      values[i] = mapper.treeToValue(array.get(i), mapper.constructType(types[i]));
    }
    return values;
  }
}
