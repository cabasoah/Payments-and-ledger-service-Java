package com.adomako.payments.mcp;

import com.adomako.payments.common.error.ApiException;
import com.adomako.payments.common.error.ErrorCode;
import java.math.BigInteger;
import java.util.Map;
import java.util.UUID;

/**
 * The arguments of one tool call, as sent by the client. Each getter checks the type and throws
 * {@code VALIDATION_FAILED} with a message that tells the model how to fix the call.
 */
public final class ToolArguments {

  private final Map<String, Object> raw;

  public ToolArguments(Map<String, Object> raw) {
    this.raw = raw == null ? Map.of() : Map.copyOf(raw);
  }

  public Map<String, Object> raw() {
    return raw;
  }

  /** A required UUID given as a string. */
  public UUID requiredUuid(String name) {
    Object value = required(name);
    if (value instanceof String text && text.length() == 36) {
      try {
        return UUID.fromString(text);
      } catch (IllegalArgumentException e) {
        // reported below
      }
    }
    throw invalid(name + " must be a UUID string, e.g. 3f6c1a52-0d7e-4c0a-9a43-6d2f1b9e8c21.");
  }

  /** A required string that is not blank. */
  public String requiredString(String name) {
    Object value = required(name);
    if (!(value instanceof String text)) {
      throw invalid(name + " must be a string.");
    }
    if (text.isBlank()) {
      throw invalid(name + " is required.");
    }
    return text;
  }

  /** A required whole number (JSON integer). Decimals and numeric strings are rejected. */
  public long requiredLong(String name) {
    Long number = wholeNumber(required(name));
    if (number == null) {
      throw invalid(
          name + " must be a whole number of minor units, e.g. 1050 for 10.50 CAD (not 10.50).");
    }
    return number;
  }

  /** An optional whole number; {@code defaultValue} when absent. */
  public int optionalInt(String name, int defaultValue) {
    Object value = raw.get(name);
    if (value == null) {
      return defaultValue;
    }
    Long number = wholeNumber(value);
    if (number == null) {
      throw invalid(name + " must be a whole number.");
    }
    if (number < Integer.MIN_VALUE || number > Integer.MAX_VALUE) {
      throw invalid(name + " is out of range.");
    }
    return number.intValue();
  }

  /**
   * The idempotency key for a write tool. Missing or blank throws {@code IDEMPOTENCY_KEY_MISSING}.
   */
  public String idempotencyKey() {
    Object value = raw.get("idempotencyKey");
    if (value != null && !(value instanceof String)) {
      throw invalid("idempotencyKey must be a string.");
    }
    if (value == null || ((String) value).isBlank()) {
      throw new ApiException(
          ErrorCode.IDEMPOTENCY_KEY_MISSING,
          "idempotencyKey is required: a new unique value such as a UUID for each new operation.");
    }
    return (String) value;
  }

  private Object required(String name) {
    Object value = raw.get(name);
    if (value == null) {
      throw invalid(name + " is required.");
    }
    return value;
  }

  /** The value as a long if it is a JSON integer, otherwise null. */
  private static Long wholeNumber(Object value) {
    if (value instanceof Integer || value instanceof Long || value instanceof Short) {
      return ((Number) value).longValue();
    }
    if (value instanceof BigInteger big && big.bitLength() < Long.SIZE) {
      return big.longValue();
    }
    return null;
  }

  private static ApiException invalid(String detail) {
    return new ApiException(ErrorCode.VALIDATION_FAILED, detail);
  }
}
