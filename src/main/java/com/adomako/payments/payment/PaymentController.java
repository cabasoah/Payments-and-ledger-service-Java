package com.adomako.payments.payment;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/payments")
@Tag(name = "Payments")
public class PaymentController {

  private static final String PROBLEM_JSON = "application/problem+json";

  private static final String INVALID_STATE_EXAMPLE =
      """
      {"type": "/problems/invalid-state-transition", "title": "Invalid payment state transition",
       "status": 409, "detail": "Payment cannot move from CAPTURED to CAPTURED",
       "instance": "/v1/payments/3f6c1a52-0d7e-4c0a-9a43-6d2f1b9e8c21/capture",
       "code": "INVALID_STATE_TRANSITION"}
      """;

  private static final String NOT_FOUND_EXAMPLE =
      """
      {"type": "/problems/payment-not-found", "title": "Payment not found", "status": 404,
       "detail": "Payment 3f6c1a52-0d7e-4c0a-9a43-6d2f1b9e8c21 does not exist.",
       "instance": "/v1/payments/3f6c1a52-0d7e-4c0a-9a43-6d2f1b9e8c21",
       "code": "PAYMENT_NOT_FOUND"}
      """;

  private final PaymentService paymentService;

  public PaymentController(PaymentService paymentService) {
    this.paymentService = paymentService;
  }

  @PostMapping
  @Operation(
      summary = "Create and authorize a payment",
      description =
          "Amounts are in minor units. tok_visa_ok is authorized; tok_declined is stored as"
              + " FAILED (still 201). No money moves until capture.",
      requestBody =
          @io.swagger.v3.oas.annotations.parameters.RequestBody(
              content =
                  @Content(
                      examples = {
                        @ExampleObject(
                            name = "Approved card",
                            value =
                                """
                                {"merchantId": "8d1f4b8e-2c55-4d55-9a8e-0c1b7e3f6a10",
                                 "amount": 1050, "currency": "CAD", "cardToken": "tok_visa_ok"}
                                """),
                        @ExampleObject(
                            name = "Declined card",
                            value =
                                """
                                {"merchantId": "8d1f4b8e-2c55-4d55-9a8e-0c1b7e3f6a10",
                                 "amount": 1050, "currency": "CAD", "cardToken": "tok_declined"}
                                """)
                      })))
  @ApiResponse(responseCode = "201", description = "Payment created (AUTHORIZED or FAILED)")
  @ApiResponse(
      responseCode = "404",
      description = "MERCHANT_NOT_FOUND",
      content =
          @Content(
              mediaType = PROBLEM_JSON,
              schema = @Schema(implementation = ProblemDetail.class)))
  @ApiResponse(
      responseCode = "422",
      description = "INVALID_CARD_TOKEN",
      content =
          @Content(
              mediaType = PROBLEM_JSON,
              schema = @Schema(implementation = ProblemDetail.class),
              examples =
                  @ExampleObject(
                      value =
                          """
                          {"type": "/problems/invalid-card-token", "title": "Invalid card token",
                           "status": 422, "detail": "Unknown card token. Use tok_visa_ok or tok_declined.",
                           "instance": "/v1/payments", "code": "INVALID_CARD_TOKEN"}
                          """)))
  public ResponseEntity<PaymentResponse> create(@Valid @RequestBody CreatePaymentRequest request) {
    PaymentResponse payment = paymentService.create(request);
    return ResponseEntity.created(URI.create("/v1/payments/" + payment.id())).body(payment);
  }

  @PostMapping("/{id}/capture")
  @Operation(summary = "Capture the full authorized amount")
  @ApiResponse(responseCode = "200", description = "Payment captured")
  @ApiResponse(
      responseCode = "404",
      description = "PAYMENT_NOT_FOUND",
      content =
          @Content(
              mediaType = PROBLEM_JSON,
              schema = @Schema(implementation = ProblemDetail.class),
              examples = @ExampleObject(value = NOT_FOUND_EXAMPLE)))
  @ApiResponse(
      responseCode = "409",
      description = "INVALID_STATE_TRANSITION",
      content =
          @Content(
              mediaType = PROBLEM_JSON,
              schema = @Schema(implementation = ProblemDetail.class),
              examples = @ExampleObject(value = INVALID_STATE_EXAMPLE)))
  public PaymentResponse capture(@PathVariable UUID id) {
    return paymentService.capture(id);
  }

  @PostMapping("/{id}/refunds")
  @ResponseStatus(HttpStatus.CREATED)
  @Operation(
      summary = "Refund part or all of the captured amount",
      requestBody =
          @io.swagger.v3.oas.annotations.parameters.RequestBody(
              content =
                  @Content(
                      examples =
                          @ExampleObject(name = "Partial refund", value = "{\"amount\": 300}"))))
  @ApiResponse(responseCode = "201", description = "Refund recorded")
  @ApiResponse(
      responseCode = "404",
      description = "PAYMENT_NOT_FOUND",
      content =
          @Content(
              mediaType = PROBLEM_JSON,
              schema = @Schema(implementation = ProblemDetail.class),
              examples = @ExampleObject(value = NOT_FOUND_EXAMPLE)))
  @ApiResponse(
      responseCode = "409",
      description = "INVALID_STATE_TRANSITION",
      content =
          @Content(
              mediaType = PROBLEM_JSON,
              schema = @Schema(implementation = ProblemDetail.class),
              examples = @ExampleObject(value = INVALID_STATE_EXAMPLE)))
  @ApiResponse(
      responseCode = "422",
      description = "REFUND_EXCEEDS_CAPTURED",
      content =
          @Content(
              mediaType = PROBLEM_JSON,
              schema = @Schema(implementation = ProblemDetail.class),
              examples =
                  @ExampleObject(
                      value =
                          """
                          {"type": "/problems/refund-exceeds-captured",
                           "title": "Refund exceeds captured amount", "status": 422,
                           "detail": "Refund of 8.00 CAD exceeds the remaining refundable 7.00 CAD",
                           "instance": "/v1/payments/3f6c1a52-0d7e-4c0a-9a43-6d2f1b9e8c21/refunds",
                           "code": "REFUND_EXCEEDS_CAPTURED"}
                          """)))
  public RefundResponse refund(@PathVariable UUID id, @Valid @RequestBody RefundRequest request) {
    return paymentService.refund(id, request);
  }

  @GetMapping("/{id}")
  @Operation(summary = "Get a payment with its refunds")
  @ApiResponse(responseCode = "200", description = "The payment")
  @ApiResponse(
      responseCode = "404",
      description = "PAYMENT_NOT_FOUND",
      content =
          @Content(
              mediaType = PROBLEM_JSON,
              schema = @Schema(implementation = ProblemDetail.class),
              examples = @ExampleObject(value = NOT_FOUND_EXAMPLE)))
  public PaymentResponse get(@PathVariable UUID id) {
    return paymentService.get(id);
  }
}
