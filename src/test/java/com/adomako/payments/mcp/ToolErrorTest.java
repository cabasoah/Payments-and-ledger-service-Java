package com.adomako.payments.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import com.adomako.payments.common.Money;
import com.adomako.payments.common.error.ApiException;
import com.adomako.payments.common.error.ErrorCode;
import com.adomako.payments.payment.IllegalPaymentTransitionException;
import com.adomako.payments.payment.PaymentStatus;
import com.adomako.payments.payment.RefundExceedsCapturedException;
import java.util.Currency;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class ToolErrorTest {

  private static final Currency CAD = Currency.getInstance("CAD");

  @Test
  void apiExceptionKeepsItsCodeTitleAndDetail() {
    ToolError error =
        ToolError.from(new ApiException(ErrorCode.PAYMENT_NOT_FOUND, "Payment x does not exist."));

    assertThat(error.code()).isEqualTo("PAYMENT_NOT_FOUND");
    assertThat(error.title()).isEqualTo(ErrorCode.PAYMENT_NOT_FOUND.title());
    assertThat(error.detail()).isEqualTo("Payment x does not exist.");
  }

  @Test
  void refundOverCapturedKeepsTheBackendMessageAndHintsAtGetPayment() {
    ToolError error =
        ToolError.from(new RefundExceedsCapturedException(Money.of(800, CAD), Money.of(750, CAD)));

    assertThat(error.code()).isEqualTo("REFUND_EXCEEDS_CAPTURED");
    assertThat(error.title()).isEqualTo(ErrorCode.REFUND_EXCEEDS_CAPTURED.title());
    assertThat(error.detail())
        .isEqualTo("Refund of 8.00 CAD exceeds the remaining refundable 7.50 CAD");
    assertThat(error.hint()).contains("get_payment");
  }

  @Test
  void illegalTransitionIsInvalidStateTransition() {
    ToolError error =
        ToolError.from(
            new IllegalPaymentTransitionException(
                PaymentStatus.AUTHORIZED, PaymentStatus.REFUNDED));

    assertThat(error.code()).isEqualTo("INVALID_STATE_TRANSITION");
    assertThat(error.detail()).isEqualTo("Payment cannot move from AUTHORIZED to REFUNDED");
    assertThat(error.hint()).contains("get_payment");
  }

  @Test
  void unexpectedErrorsDoNotLeakTheirMessage() {
    ToolError error =
        ToolError.from(new IllegalStateException("connection to db-internal:5432 refused"));

    assertThat(error.code()).isEqualTo("INTERNAL_ERROR");
    assertThat(error.title()).isEqualTo(ErrorCode.INTERNAL_ERROR.title());
    assertThat(error.detail()).isEqualTo("An unexpected error occurred.");
    assertThat(error.toString()).doesNotContain("db-internal");
  }

  @ParameterizedTest
  @EnumSource(
      value = ErrorCode.class,
      names = {
        "REFUND_EXCEEDS_CAPTURED",
        "INVALID_STATE_TRANSITION",
        "IDEMPOTENCY_KEY_MISSING",
        "IDEMPOTENCY_KEY_REUSED",
        "IDEMPOTENCY_REQUEST_IN_PROGRESS",
        "INVALID_CARD_TOKEN",
        "PAYMENT_NOT_FOUND",
        "VALIDATION_FAILED",
        "INTERNAL_ERROR"
      })
  void errorsAnAgentCanActOnCarryAHint(ErrorCode code) {
    ToolError error = ToolError.from(new ApiException(code, "detail"));

    assertThat(error.hint()).isNotBlank();
  }

  @Test
  void reusedKeyHintSaysToUseANewKey() {
    ToolError error = ToolError.from(new ApiException(ErrorCode.IDEMPOTENCY_KEY_REUSED, "reused"));

    assertThat(error.hint()).contains("new idempotencyKey");
  }

  @Test
  void inProgressHintSaysToRetryWithTheSameKey() {
    ToolError error =
        ToolError.from(new ApiException(ErrorCode.IDEMPOTENCY_REQUEST_IN_PROGRESS, "busy"));

    assertThat(error.hint()).contains("same idempotencyKey");
  }
}
