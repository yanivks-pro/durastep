package io.github.yanivks_pro.durastep.api;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.Objects;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Encrypts workflow data with AES-GCM before it reaches the database. Each piece of data is stored
 * as an envelope that names the key it was encrypted with:
 *
 * <pre>{"enc":"v1","key":"k2","data":"…"}</pre>
 *
 * <p>The application supplies the keys and names one of them current. New data is encrypted with
 * the current key; data is decrypted with the key its envelope names, so keys can be rotated by
 * adding a new current key and keeping the old ones until no stored data uses them. Data stored
 * before encryption was turned on is read as it is.
 *
 * <pre>{@code
 * PayloadCodec codec = AesGcmPayloadCodec.withKeys(Map.of("k1", oldKey, "k2", newKey), "k2");
 * DurastepEngine engine = DurastepEngine.builder().dataSource(dataSource).payloadCodec(codec).build();
 * }</pre>
 */
public final class AesGcmPayloadCodec implements PayloadCodec {

  private static final String VERSION = "v1";
  private static final String CIPHER = "AES/GCM/NoPadding";
  private static final int IV_BYTES = 12;
  private static final int TAG_BITS = 128;
  private static final JsonMapper JSON = JsonMapper.builder().build();

  private final Map<String, SecretKey> keys;
  private final String currentKeyId;
  private final SecureRandom random = new SecureRandom();

  private AesGcmPayloadCodec(Map<String, SecretKey> keys, String currentKeyId) {
    this.keys = Map.copyOf(keys);
    this.currentKeyId = currentKeyId;
  }

  /**
   * Creates a codec with the given keys.
   *
   * @param keys AES keys of 128, 192 or 256 bits, by key ID
   * @param currentKeyId the ID of the key that encrypts new data
   * @return the codec
   * @throws IllegalArgumentException if the current key is missing or a key is not an AES key
   */
  public static AesGcmPayloadCodec withKeys(Map<String, SecretKey> keys, String currentKeyId) {
    Objects.requireNonNull(keys, "keys");
    Objects.requireNonNull(currentKeyId, "currentKeyId");
    if (!keys.containsKey(currentKeyId)) {
      throw new IllegalArgumentException(
          "the current key " + currentKeyId + " is not among the keys");
    }
    keys.forEach(
        (id, key) -> {
          int bytes = key.getEncoded().length;
          if (!key.getAlgorithm().equals("AES") || (bytes != 16 && bytes != 24 && bytes != 32)) {
            throw new IllegalArgumentException(
                "key " + id + " is not an AES key of 128, 192 or 256 bits");
          }
        });
    return new AesGcmPayloadCodec(keys, currentKeyId);
  }

  /**
   * Generates a new random 256-bit AES key, for example to store in a secret manager.
   *
   * @return the key
   */
  public static SecretKey newKey() {
    try {
      KeyGenerator generator = KeyGenerator.getInstance("AES");
      generator.init(256);
      return generator.generateKey();
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("AES is not available", e);
    }
  }

  @Override
  public String encode(String json) {
    byte[] iv = new byte[IV_BYTES];
    random.nextBytes(iv);
    try {
      Cipher cipher = Cipher.getInstance(CIPHER);
      cipher.init(Cipher.ENCRYPT_MODE, keys.get(currentKeyId), new GCMParameterSpec(TAG_BITS, iv));
      cipher.updateAAD(associatedData(currentKeyId));
      byte[] encrypted = cipher.doFinal(json.getBytes(StandardCharsets.UTF_8));
      byte[] data =
          ByteBuffer.allocate(iv.length + encrypted.length).put(iv).put(encrypted).array();
      ObjectNode envelope =
          JSON.createObjectNode()
              .put("enc", VERSION)
              .put("key", currentKeyId)
              .put("data", Base64.getEncoder().encodeToString(data));
      return JSON.writeValueAsString(envelope);
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("could not encrypt with key " + currentKeyId, e);
    }
  }

  @Override
  public String decode(String storedJson) {
    JsonNode stored = JSON.readTree(storedJson);
    if (!isEnvelope(stored)) {
      return storedJson;
    }
    String keyId = stored.path("key").asString();
    SecretKey key = keys.get(keyId);
    if (key == null) {
      throw new IllegalStateException(
          "this data is encrypted with key " + keyId + ", which this codec does not have");
    }
    byte[] data = Base64.getDecoder().decode(stored.path("data").asString());
    try {
      Cipher cipher = Cipher.getInstance(CIPHER);
      cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, data, 0, IV_BYTES));
      cipher.updateAAD(associatedData(keyId));
      byte[] plain = cipher.doFinal(data, IV_BYTES, data.length - IV_BYTES);
      return new String(plain, StandardCharsets.UTF_8);
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException(
          "could not decrypt data encrypted with key "
              + keyId
              + ": the key is wrong or the data was changed",
          e);
    }
  }

  static boolean isEnvelope(JsonNode stored) {
    return stored.isObject()
        && VERSION.equals(stored.path("enc").asString(null))
        && stored.has("key")
        && stored.has("data");
  }

  private static byte[] associatedData(String keyId) {
    return ("durastep:" + VERSION + ":" + keyId).getBytes(StandardCharsets.UTF_8);
  }
}
