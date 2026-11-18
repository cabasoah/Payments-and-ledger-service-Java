package com.adomako.payments.common.error;

import java.net.URI;
import java.util.Locale;
import org.springframework.http.HttpStatus;

/** Every error code the API can return, with its HTTP status and problem title. */
public enum ErrorCode {
  VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "Request validation failed"),
  MALFORMED_REQUEST(HttpStatus.BAD_REQUEST, "Malformed request body"),
  MERCHANT_NOT_FOUND(HttpStatus.NOT_FOUND, "Merchant not found"),
  PAYMENT_NOT_FOUND(HttpStatus.NOT_FOUND, "Payment not found"),
  INVALID_STATE_TRANSITION(HttpStatus.CONFLICT, "Invalid payment state transition"),
  INVALID_CARD_TOKEN(HttpStatus.UNPROCESSABLE_CONTENT, "Invalid card token"),
  REFUND_EXCEEDS_CAPTURED(HttpStatus.UNPROCESSABLE_CONTENT, "Refund exceeds captured amount"),
  INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "Internal error");

  private final HttpStatus status;
  private final String title;

  ErrorCode(HttpStatus status, String title) {
    this.status = status;
    this.title = title;
  }

  public HttpStatus status() {
    return status;
  }

  public String title() {
    return title;
  }

  /** Problem type URI, e.g. {@code /problems/refund-exceeds-captured}. */
  public URI type() {
    return URI.create("/problems/" + name().toLowerCase(Locale.ROOT).replace('_', '-'));
  }
}
