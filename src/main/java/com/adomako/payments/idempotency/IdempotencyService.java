package com.adomako.payments.idempotency;

import com.adomako.payments.common.error.ApiException;
import com.adomako.payments.common.error.ErrorCode;
import com.adomako.payments.idempotency.IdempotencyKeyRepository.StoredKey;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Runs an operation at most once per (merchant, Idempotency-Key). The key row is written in the
 * same transaction as the operation, so either both commit or neither does. Only successful results
 * are stored: if the operation throws, the key row rolls back with it.
 */
@Service
public class IdempotencyService {

  static final int MAX_KEY_LENGTH = 255;

  private final IdempotencyKeyRepository keys;
  private final JsonMapper jsonMapper;

  public IdempotencyService(IdempotencyKeyRepository keys, JsonMapper jsonMapper) {
    this.keys = keys;
    this.jsonMapper = jsonMapper;
  }

  /**
   * @param fingerprint identifies the request (see {@link RequestFingerprint})
   * @param successStatus HTTP status to store with the result, e.g. 201
   * @param action the business operation; joins this transaction
   */
  @Transactional
  public <T> IdempotentResponse<T> execute(
      UUID merchantId,
      String key,
      String fingerprint,
      Class<T> responseType,
      int successStatus,
      Supplier<T> action) {
    requireValidKey(key);

    // Fails fast instead of waiting when an identical request is running right now.
    if (!keys.tryLock(merchantId, key)) {
      throw inProgress();
    }

    Optional<StoredKey> existing = keys.find(merchantId, key);
    if (existing.isPresent()) {
      StoredKey stored = existing.get();
      if (stored.expired()) {
        keys.delete(merchantId, key);
      } else if (!stored.requestHash().equals(fingerprint)) {
        throw new ApiException(
            ErrorCode.IDEMPOTENCY_KEY_REUSED,
            "This Idempotency-Key was already used for a different request.");
      } else if (!"COMPLETED".equals(stored.status())) {
        throw inProgress();
      } else {
        T body = jsonMapper.readValue(stored.responseBody(), responseType);
        return new IdempotentResponse<>(stored.responseStatus(), body, true);
      }
    }

    keys.insertInProgress(merchantId, key, fingerprint);
    T body = action.get();
    keys.complete(merchantId, key, successStatus, jsonMapper.writeValueAsString(body));
    return new IdempotentResponse<>(successStatus, body, false);
  }

  private static void requireValidKey(String key) {
    if (key == null || key.isBlank()) {
      throw new ApiException(
          ErrorCode.IDEMPOTENCY_KEY_MISSING,
          "Send a unique Idempotency-Key header with this request.");
    }
    if (key.length() > MAX_KEY_LENGTH) {
      throw new ApiException(
          ErrorCode.VALIDATION_FAILED,
          "Idempotency-Key must be at most " + MAX_KEY_LENGTH + " characters.");
    }
  }

  private static ApiException inProgress() {
    return new ApiException(
        ErrorCode.IDEMPOTENCY_REQUEST_IN_PROGRESS,
        "A request with this Idempotency-Key is still being processed. Retry shortly.");
  }
}
