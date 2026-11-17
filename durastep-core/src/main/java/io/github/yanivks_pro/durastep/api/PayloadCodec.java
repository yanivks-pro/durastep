package io.github.yanivks_pro.durastep.api;

/**
 * Transforms workflow data on its way into and out of the database, for example to encrypt it; set
 * it with {@link DurastepEngine.Builder#payloadCodec(PayloadCodec)}. It applies to workflow inputs
 * and results, activity arguments and results, and signal payloads. IDs, names, statuses and error
 * messages are not encoded, so tools can still show them.
 *
 * <p>{@link AesGcmPayloadCodec} encrypts with AES-GCM.
 */
public interface PayloadCodec {

  /**
   * Turns a piece of data, as JSON, into the JSON to store.
   *
   * @param json the data as JSON
   * @return the JSON to store, such as an envelope with the encrypted data
   */
  String encode(String json);

  /**
   * Turns stored JSON back into the original data. Data that was stored without this codec, for
   * example before encryption was turned on, must be returned unchanged.
   *
   * @param storedJson the stored JSON
   * @return the original data as JSON
   */
  String decode(String storedJson);
}
