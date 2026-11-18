package com.adomako.payments.common.error;

import com.adomako.payments.payment.IllegalPaymentTransitionException;
import com.adomako.payments.payment.RefundExceedsCapturedException;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.TypeMismatchException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Turns every error into an RFC 7807 problem ({@code application/problem+json}) with a {@code code}
 * property. Spring's own MVC errors (404 for unknown paths, 405, ...) also get a code: the HTTP
 * status name.
 */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

  private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

  @ExceptionHandler(ApiException.class)
  ResponseEntity<Object> handleApi(ApiException ex, HttpServletRequest request) {
    return problem(ex.code(), ex.getMessage(), request.getRequestURI());
  }

  @ExceptionHandler(IllegalPaymentTransitionException.class)
  ResponseEntity<Object> handleTransition(
      IllegalPaymentTransitionException ex, HttpServletRequest request) {
    return problem(ErrorCode.INVALID_STATE_TRANSITION, ex.getMessage(), request.getRequestURI());
  }

  @ExceptionHandler(RefundExceedsCapturedException.class)
  ResponseEntity<Object> handleRefundExceeds(
      RefundExceedsCapturedException ex, HttpServletRequest request) {
    return problem(ErrorCode.REFUND_EXCEEDS_CAPTURED, ex.getMessage(), request.getRequestURI());
  }

  @ExceptionHandler(Exception.class)
  ResponseEntity<Object> handleUnexpected(Exception ex, HttpServletRequest request) {
    log.error("Unhandled error on {} {}", request.getMethod(), request.getRequestURI(), ex);
    return problem(
        ErrorCode.INTERNAL_ERROR, "An unexpected error occurred.", request.getRequestURI());
  }

  @Override
  protected ResponseEntity<Object> handleMethodArgumentNotValid(
      MethodArgumentNotValidException ex,
      HttpHeaders headers,
      HttpStatusCode status,
      WebRequest request) {
    List<Map<String, String>> errors =
        ex.getBindingResult().getFieldErrors().stream()
            .map(e ->
                Map.of("field", e.getField(), "message", String.valueOf(e.getDefaultMessage())))
            .toList();
    ResponseEntity<Object> response =
        problem(ErrorCode.VALIDATION_FAILED, "One or more fields are invalid.", path(request));
    ((ProblemDetail) response.getBody()).setProperty("errors", errors);
    return response;
  }

  @Override
  protected ResponseEntity<Object> handleTypeMismatch(
      TypeMismatchException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
    String name = ex.getPropertyName() == null ? "value" : ex.getPropertyName();
    return problem(ErrorCode.VALIDATION_FAILED, "Invalid " + name + ".", path(request));
  }

  @Override
  protected ResponseEntity<Object> handleHttpMessageNotReadable(
      HttpMessageNotReadableException ex,
      HttpHeaders headers,
      HttpStatusCode status,
      WebRequest request) {
    return problem(
        ErrorCode.MALFORMED_REQUEST, "Request body is missing or not valid JSON.", path(request));
  }

  /** Adds {@code code} and {@code instance} to Spring's own problem responses. */
  @Override
  protected ResponseEntity<Object> handleExceptionInternal(
      Exception ex,
      Object body,
      HttpHeaders headers,
      HttpStatusCode statusCode,
      WebRequest request) {
    if (body instanceof ProblemDetail problem && problem.getProperties() == null) {
      HttpStatus status = HttpStatus.resolve(statusCode.value());
      problem.setProperty("code", status == null ? "HTTP_" + statusCode.value() : status.name());
      problem.setInstance(URI.create(path(request)));
    }
    return super.handleExceptionInternal(ex, body, headers, statusCode, request);
  }

  private static ResponseEntity<Object> problem(ErrorCode code, String detail, String path) {
    ProblemDetail problem = ProblemDetail.forStatusAndDetail(code.status(), detail);
    problem.setType(code.type());
    problem.setTitle(code.title());
    problem.setInstance(URI.create(path));
    problem.setProperty("code", code.name());
    return ResponseEntity.status(code.status()).body(problem);
  }

  private static String path(WebRequest request) {
    return ((ServletWebRequest) request).getRequest().getRequestURI();
  }
}
