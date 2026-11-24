package com.adomako.payments.mcp;

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
    throw new UnsupportedOperationException("not implemented yet");
  }
}
