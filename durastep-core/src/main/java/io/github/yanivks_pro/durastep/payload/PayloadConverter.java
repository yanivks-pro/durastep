package io.github.yanivks_pro.durastep.payload;

import java.lang.reflect.Type;

public interface PayloadConverter {

  Payload toPayload(Object value);

  Object fromPayload(Payload payload, Type type);
}
