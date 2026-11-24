package com.adomako.payments.mcp;

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
    throw new UnsupportedOperationException("not implemented yet");
  }

  /** A required string that is not blank. */
  public String requiredString(String name) {
    throw new UnsupportedOperationException("not implemented yet");
  }

  /** A required whole number (JSON integer). Decimals and numeric strings are rejected. */
  public long requiredLong(String name) {
    throw new UnsupportedOperationException("not implemented yet");
  }

  /** An optional whole number; {@code defaultValue} when absent. */
  public int optionalInt(String name, int defaultValue) {
    throw new UnsupportedOperationException("not implemented yet");
  }

  /**
   * The idempotency key for a write tool. Missing or blank throws {@code IDEMPOTENCY_KEY_MISSING}.
   */
  public String idempotencyKey() {
    throw new UnsupportedOperationException("not implemented yet");
  }
}
