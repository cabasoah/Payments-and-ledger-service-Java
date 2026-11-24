package com.adomako.payments.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.adomako.payments.common.error.ApiException;
import com.adomako.payments.common.error.ErrorCode;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class ToolArgumentsTest {

  private static ToolArguments args(String name, Object value) {
    Map<String, Object> raw = new HashMap<>();
    raw.put(name, value);
    return new ToolArguments(raw);
  }

  private static void assertRejected(ThrowingCallable call, ErrorCode code, String detailPart) {
    assertThatThrownBy(call)
        .isInstanceOfSatisfying(
            ApiException.class,
            e -> {
              assertThat(e.code()).isEqualTo(code);
              assertThat(e.getMessage()).contains(detailPart);
            });
  }

  @Test
  void nullArgumentsAreTreatedAsEmpty() {
    assertThat(new ToolArguments(null).raw()).isEmpty();
  }

  @Nested
  class Uuid {

    @Test
    void parsesAUuidString() {
      UUID id = UUID.randomUUID();

      assertThat(args("paymentId", id.toString()).requiredUuid("paymentId")).isEqualTo(id);
    }

    @Test
    void missingIsRejected() {
      assertRejected(
          () -> new ToolArguments(Map.of()).requiredUuid("paymentId"),
          ErrorCode.VALIDATION_FAILED,
          "paymentId is required");
    }

    @Test
    void malformedIsRejected() {
      assertRejected(
          () -> args("paymentId", "not-a-uuid").requiredUuid("paymentId"),
          ErrorCode.VALIDATION_FAILED,
          "paymentId must be a UUID");
    }

    @Test
    void nonStringIsRejected() {
      assertRejected(
          () -> args("paymentId", 123).requiredUuid("paymentId"),
          ErrorCode.VALIDATION_FAILED,
          "paymentId must be a UUID");
    }
  }

  @Nested
  class Strings {

    @Test
    void returnsTheString() {
      assertThat(args("cardToken", "tok_visa_ok").requiredString("cardToken"))
          .isEqualTo("tok_visa_ok");
    }

    @Test
    void missingIsRejected() {
      assertRejected(
          () -> new ToolArguments(Map.of()).requiredString("cardToken"),
          ErrorCode.VALIDATION_FAILED,
          "cardToken is required");
    }

    @Test
    void blankIsRejected() {
      assertRejected(
          () -> args("cardToken", "  ").requiredString("cardToken"),
          ErrorCode.VALIDATION_FAILED,
          "cardToken is required");
    }

    @Test
    void nonStringIsRejected() {
      assertRejected(
          () -> args("currency", 124).requiredString("currency"),
          ErrorCode.VALIDATION_FAILED,
          "currency must be a string");
    }
  }

  @Nested
  class WholeNumbers {

    @Test
    void acceptsJsonIntegers() {
      assertThat(args("amount", 1050).requiredLong("amount")).isEqualTo(1050L);
      assertThat(args("amount", 5_000_000_000L).requiredLong("amount")).isEqualTo(5_000_000_000L);
    }

    @Test
    void missingIsRejected() {
      assertRejected(
          () -> new ToolArguments(Map.of()).requiredLong("amount"),
          ErrorCode.VALIDATION_FAILED,
          "amount is required");
    }

    @Test
    void decimalsAreRejectedWithAMinorUnitsHint() {
      // 10.50 is the classic mistake: the model sent dollars instead of cents.
      assertRejected(
          () -> args("amount", 10.50).requiredLong("amount"),
          ErrorCode.VALIDATION_FAILED,
          "amount must be a whole number of minor units");
    }

    @Test
    void wholeValuedDecimalsAreAlsoRejected() {
      assertRejected(
          () -> args("amount", 1050.0).requiredLong("amount"),
          ErrorCode.VALIDATION_FAILED,
          "amount must be a whole number");
    }

    @Test
    void numericStringsAreRejected() {
      assertRejected(
          () -> args("amount", "1050").requiredLong("amount"),
          ErrorCode.VALIDATION_FAILED,
          "amount must be a whole number");
    }

    @Test
    void optionalIntDefaultsWhenAbsent() {
      assertThat(new ToolArguments(Map.of()).optionalInt("limit", 20)).isEqualTo(20);
    }

    @Test
    void optionalIntUsesTheGivenValue() {
      assertThat(args("limit", 5).optionalInt("limit", 20)).isEqualTo(5);
    }

    @Test
    void optionalIntRejectsDecimals() {
      assertRejected(
          () -> args("limit", 2.5).optionalInt("limit", 20),
          ErrorCode.VALIDATION_FAILED,
          "limit must be a whole number");
    }

    @Test
    void optionalIntRejectsValuesBeyondIntRange() {
      assertRejected(
          () -> args("limit", 5_000_000_000L).optionalInt("limit", 20),
          ErrorCode.VALIDATION_FAILED,
          "limit");
    }
  }

  @Nested
  class IdempotencyKey {

    @Test
    void returnsTheKey() {
      assertThat(args("idempotencyKey", "key-1").idempotencyKey()).isEqualTo("key-1");
    }

    @Test
    void missingIsIdempotencyKeyMissing() {
      assertRejected(
          () -> new ToolArguments(Map.of()).idempotencyKey(),
          ErrorCode.IDEMPOTENCY_KEY_MISSING,
          "idempotencyKey");
    }

    @Test
    void blankIsIdempotencyKeyMissing() {
      assertRejected(
          () -> args("idempotencyKey", " ").idempotencyKey(),
          ErrorCode.IDEMPOTENCY_KEY_MISSING,
          "idempotencyKey");
    }

    @Test
    void nonStringIsRejected() {
      assertRejected(
          () -> args("idempotencyKey", 42).idempotencyKey(),
          ErrorCode.VALIDATION_FAILED,
          "idempotencyKey must be a string");
    }
  }
}
