package com.adomako.payments.mcp;

import com.adomako.payments.common.error.ApiException;
import com.adomako.payments.common.error.ErrorCode;
import com.adomako.payments.payment.IllegalPaymentTransitionException;
import com.adomako.payments.payment.RefundExceedsCapturedException;

/**
 * A failed tool call as the model sees it: the same {@code code}, {@code title} and {@code detail}
 * as the REST API's problem responses, plus a {@code hint} on what to do next (null when there is
 * nothing useful to add).
 */
public record ToolError(String code, String title, String detail, String hint) {

  /**
   * Maps an exception to the error the model sees, using the same codes as {@link
   * com.adomako.payments.common.error.ApiExceptionHandler}. Unexpected exceptions become {@code
   * INTERNAL_ERROR} with a generic detail, so internals are never shown to the model.
   */
  public static ToolError from(Throwable error) {
    return switch (error) {
      case ApiException e -> of(e.code(), e.getMessage());
      case IllegalPaymentTransitionException e ->
          of(ErrorCode.INVALID_STATE_TRANSITION, e.getMessage());
      case RefundExceedsCapturedException e ->
          of(ErrorCode.REFUND_EXCEEDS_CAPTURED, e.getMessage());
      default -> of(ErrorCode.INTERNAL_ERROR, "An unexpected error occurred.");
    };
  }

  private static ToolError of(ErrorCode code, String detail) {
    return new ToolError(code.name(), code.title(), detail, hint(code));
  }

  private static String hint(ErrorCode code) {
    return switch (code) {
      case REFUND_EXCEEDS_CAPTURED ->
          "Call get_payment to see the refundable amount, and refund at most that.";
      case INVALID_STATE_TRANSITION ->
          "Call get_payment to check the status. Only AUTHORIZED payments can be captured; only"
              + " CAPTURED or PARTIALLY_REFUNDED payments can be refunded.";
      case IDEMPOTENCY_KEY_MISSING ->
          "Send idempotencyKey: a new unique value such as a UUID for each new operation.";
      case IDEMPOTENCY_KEY_REUSED ->
          "Use a new idempotencyKey for a different request; reuse a key only to retry the exact"
              + " same call.";
      case IDEMPOTENCY_REQUEST_IN_PROGRESS ->
          "Wait a moment, then retry the same call with the same idempotencyKey.";
      case INVALID_CARD_TOKEN -> "Use tok_visa_ok (approved) or tok_declined (declined).";
      case PAYMENT_NOT_FOUND -> "Check the paymentId; list_payments shows a merchant's payments.";
      case VALIDATION_FAILED -> "Fix the arguments named in detail and call again.";
      case INTERNAL_ERROR ->
          "Retrying a write with the same idempotencyKey is safe. If it fails again, stop and"
              + " tell the user.";
      default -> null;
    };
  }
}
