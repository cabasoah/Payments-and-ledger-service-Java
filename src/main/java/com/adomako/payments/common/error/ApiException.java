package com.adomako.payments.common.error;

/** An error with a known API error code. The message becomes the problem's {@code detail}. */
public class ApiException extends RuntimeException {

  private final ErrorCode code;

  public ApiException(ErrorCode code, String detail) {
    super(detail);
    this.code = code;
  }

  public ErrorCode code() {
    return code;
  }
}
