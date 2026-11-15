package io.github.yanivks_pro.durastep.activity;

import io.github.yanivks_pro.durastep.payload.Payload;
import io.github.yanivks_pro.durastep.runtime.Failure;

public sealed interface ActivityResult {

  record Completed(Payload result) implements ActivityResult {}

  record Failed(Failure failure, boolean retryable) implements ActivityResult {}
}
