package io.github.yanivks_pro.durastep.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Base64;
import java.util.Map;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

class AesGcmPayloadCodecTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final String SECRET = "{\"card\":\"4111 1111 1111 1111\"}";

  private final SecretKey oldKey = AesGcmPayloadCodec.newKey();
  private final SecretKey newKey = AesGcmPayloadCodec.newKey();
  private final PayloadCodec codec = AesGcmPayloadCodec.withKeys(Map.of("k1", oldKey), "k1");

  @Test
  void dataComesBackAsItWasAndIsStoredAsAnEnvelopeWithoutThePlaintext() {
    String stored = codec.encode(SECRET);

    JsonNode envelope = JSON.readTree(stored);
    assertThat(envelope.path("enc").asString()).isEqualTo("v1");
    assertThat(envelope.path("key").asString()).isEqualTo("k1");
    assertThat(stored).doesNotContain("4111");
    assertThat(codec.decode(stored)).isEqualTo(SECRET);
  }

  @Test
  void theSameDataIsEncryptedDifferentlyEveryTime() {
    assertThat(codec.encode(SECRET)).isNotEqualTo(codec.encode(SECRET));
  }

  @Test
  void afterARotationOldDataIsStillReadAndNewDataUsesTheNewKey() {
    String storedWithTheOldKey = codec.encode(SECRET);
    PayloadCodec rotated = AesGcmPayloadCodec.withKeys(Map.of("k1", oldKey, "k2", newKey), "k2");

    String storedWithTheNewKey = rotated.encode(SECRET);

    assertThat(rotated.decode(storedWithTheOldKey)).isEqualTo(SECRET);
    assertThat(JSON.readTree(storedWithTheNewKey).path("key").asString()).isEqualTo("k2");
    assertThat(rotated.decode(storedWithTheNewKey)).isEqualTo(SECRET);
  }

  @Test
  void dataStoredBeforeEncryptionWasTurnedOnIsReadAsItIs() {
    assertThat(codec.decode(SECRET)).isEqualTo(SECRET);
    assertThat(codec.decode("\"plain text\"")).isEqualTo("\"plain text\"");
  }

  @Test
  void anUnknownKeyChangedDataOrAChangedKeyNameAreRejected() {
    String stored = codec.encode(SECRET);
    PayloadCodec withoutTheKey = AesGcmPayloadCodec.withKeys(Map.of("k2", newKey), "k2");
    ObjectNode changedData = (ObjectNode) JSON.readTree(stored);
    byte[] data = Base64.getDecoder().decode(changedData.path("data").asString());
    data[data.length - 1] ^= 1;
    changedData.put("data", Base64.getEncoder().encodeToString(data));
    ObjectNode renamed = (ObjectNode) JSON.readTree(stored);
    renamed.put("key", "k2");
    PayloadCodec sameKeyUnderAnotherName =
        AesGcmPayloadCodec.withKeys(Map.of("k1", oldKey, "k2", oldKey), "k1");

    assertThatThrownBy(() -> withoutTheKey.decode(stored))
        .hasMessageContaining("encrypted with key k1, which this codec does not have");
    assertThatThrownBy(() -> codec.decode(changedData.toString()))
        .hasMessageContaining("the key is wrong or the data was changed");
    assertThatThrownBy(() -> sameKeyUnderAnotherName.decode(renamed.toString()))
        .hasMessageContaining("the key is wrong or the data was changed");
  }

  @Test
  void keysAreChecked() {
    SecretKey notAes = new SecretKeySpec(new byte[16], "HmacSHA256");

    assertThatThrownBy(() -> AesGcmPayloadCodec.withKeys(Map.of("k1", oldKey), "k2"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> AesGcmPayloadCodec.withKeys(Map.of("k1", notAes), "k1"))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
