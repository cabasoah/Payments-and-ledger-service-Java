package com.adomako.payments.mcp;

import com.adomako.payments.common.error.ApiException;
import com.adomako.payments.common.error.ErrorCode;
import jakarta.validation.Validator;
import java.util.stream.Collectors;

/** Applies a request's Bean Validation constraints, the same ones REST applies with @Valid. */
final class RequestValidation {

  private RequestValidation() {}

  /** Throws {@code VALIDATION_FAILED} naming every invalid field, e.g. "amount must be ...". */
  static void requireValid(Validator validator, Object request) {
    String problems =
        validator.validate(request).stream()
            .map(v -> v.getPropertyPath() + " " + v.getMessage())
            .sorted()
            .collect(Collectors.joining("; "));
    if (!problems.isEmpty()) {
      throw new ApiException(ErrorCode.VALIDATION_FAILED, "Invalid arguments: " + problems + ".");
    }
  }
}
