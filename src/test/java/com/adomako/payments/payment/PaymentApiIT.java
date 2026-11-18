package com.adomako.payments.payment;

import static org.assertj.core.api.Assertions.assertThat;

import com.adomako.payments.AbstractApiIT;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

class PaymentApiIT extends AbstractApiIT {

  // ---- happy paths ----

  @Test
  void authorizeCaptureAndRefundInTwoParts() {
    UUID merchantId = createMerchant();

    ResponseEntity<PaymentResponse> created = createPayment(merchantId, 1000, "CAD", "tok_visa_ok");
    assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    PaymentResponse payment = created.getBody();
    assertThat(payment.status()).isEqualTo(PaymentStatus.AUTHORIZED);
    assertThat(payment.merchantId()).isEqualTo(merchantId);
    assertThat(payment.amount()).isEqualTo(1000);
    assertThat(payment.currency()).isEqualTo("CAD");
    assertThat(payment.capturedAmount()).isZero();
    assertThat(payment.processorRef()).isNotBlank();
    assertThat(created.getHeaders().getLocation()).hasPath("/v1/payments/" + payment.id());

    ResponseEntity<PaymentResponse> captured = capture(payment.id());
    assertThat(captured.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(captured.getBody().status()).isEqualTo(PaymentStatus.CAPTURED);
    assertThat(captured.getBody().capturedAmount()).isEqualTo(1000);

    ResponseEntity<RefundResponse> firstRefund = refund(payment.id(), 300);
    assertThat(firstRefund.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(firstRefund.getBody().paymentId()).isEqualTo(payment.id());
    assertThat(firstRefund.getBody().amount()).isEqualTo(300);
    assertThat(firstRefund.getBody().currency()).isEqualTo("CAD");

    PaymentResponse partiallyRefunded = getPayment(payment.id()).getBody();
    assertThat(partiallyRefunded.status()).isEqualTo(PaymentStatus.PARTIALLY_REFUNDED);
    assertThat(partiallyRefunded.refundedAmount()).isEqualTo(300);
    assertThat(partiallyRefunded.refunds())
        .extracting(RefundResponse::amount)
        .containsExactly(300L);

    assertThat(refund(payment.id(), 700).getStatusCode()).isEqualTo(HttpStatus.CREATED);

    ResponseEntity<PaymentResponse> fetched = getPayment(payment.id());
    assertThat(fetched.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(fetched.getBody().status()).isEqualTo(PaymentStatus.REFUNDED);
    assertThat(fetched.getBody().capturedAmount()).isEqualTo(1000);
    assertThat(fetched.getBody().refundedAmount()).isEqualTo(1000);
    assertThat(fetched.getBody().refunds())
        .extracting(RefundResponse::amount)
        .containsExactly(300L, 700L);
  }

  @Test
  void zeroDecimalCurrencyEndToEnd() {
    PaymentResponse payment = createPayment(createMerchant(), 5000, "JPY", "tok_visa_ok").getBody();

    capture(payment.id());
    refund(payment.id(), 1200);

    PaymentResponse fetched = getPayment(payment.id()).getBody();
    assertThat(fetched.currency()).isEqualTo("JPY");
    assertThat(fetched.status()).isEqualTo(PaymentStatus.PARTIALLY_REFUNDED);
    assertThat(fetched.refundedAmount()).isEqualTo(1200);
  }

  @Test
  void declinedCardCreatesFailedPayment() {
    ResponseEntity<PaymentResponse> response =
        createPayment(createMerchant(), 1000, "CAD", "tok_declined");

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(response.getBody().status()).isEqualTo(PaymentStatus.FAILED);
    assertThat(response.getBody().capturedAmount()).isZero();
  }

  // ---- error codes ----

  @Test
  void captureOfFailedPaymentIsInvalidTransition() {
    PaymentResponse failed = createPayment(createMerchant(), 1000, "CAD", "tok_declined").getBody();
    String path = "/v1/payments/" + failed.id() + "/capture";

    assertProblem(
        postForMap(path, Map.of()), HttpStatus.CONFLICT, "INVALID_STATE_TRANSITION", path);
  }

  @Test
  void captureTwiceIsInvalidTransition() {
    PaymentResponse payment = capturedPayment(1000);
    String path = "/v1/payments/" + payment.id() + "/capture";

    assertProblem(
        postForMap(path, Map.of()), HttpStatus.CONFLICT, "INVALID_STATE_TRANSITION", path);
    assertThat(getPayment(payment.id()).getBody().capturedAmount()).isEqualTo(1000);
  }

  @Test
  void refundBeforeCaptureIsInvalidTransition() {
    PaymentResponse payment = authorizedPayment(1000);
    String path = "/v1/payments/" + payment.id() + "/refunds";

    assertProblem(
        postForMap(path, Map.of("amount", 100)),
        HttpStatus.CONFLICT,
        "INVALID_STATE_TRANSITION",
        path);
  }

  @Test
  void refundAfterFullRefundIsInvalidTransition() {
    PaymentResponse payment = capturedPayment(1000);
    refund(payment.id(), 1000);
    String path = "/v1/payments/" + payment.id() + "/refunds";

    assertProblem(
        postForMap(path, Map.of("amount", 1)),
        HttpStatus.CONFLICT,
        "INVALID_STATE_TRANSITION",
        path);
  }

  @Test
  void refundOverCapturedIsRejected() {
    PaymentResponse payment = capturedPayment(1000);
    String path = "/v1/payments/" + payment.id() + "/refunds";

    assertProblem(
        postForMap(path, Map.of("amount", 1001)),
        HttpStatus.UNPROCESSABLE_CONTENT,
        "REFUND_EXCEEDS_CAPTURED",
        path);
    PaymentResponse unchanged = getPayment(payment.id()).getBody();
    assertThat(unchanged.status()).isEqualTo(PaymentStatus.CAPTURED);
    assertThat(unchanged.refunds()).isEmpty();
  }

  @Test
  void refundOverRemainingIsRejected() {
    PaymentResponse payment = capturedPayment(1000);
    refund(payment.id(), 300);
    String path = "/v1/payments/" + payment.id() + "/refunds";

    assertProblem(
        postForMap(path, Map.of("amount", 701)),
        HttpStatus.UNPROCESSABLE_CONTENT,
        "REFUND_EXCEEDS_CAPTURED",
        path);
    PaymentResponse unchanged = getPayment(payment.id()).getBody();
    assertThat(unchanged.refundedAmount()).isEqualTo(300);
    assertThat(unchanged.refunds()).hasSize(1);
  }

  @Test
  void unknownCardTokenIsRejected() {
    assertProblem(
        postForMap("/v1/payments", paymentBody(createMerchant(), 1000, "CAD", "tok_unknown")),
        HttpStatus.UNPROCESSABLE_CONTENT,
        "INVALID_CARD_TOKEN",
        "/v1/payments");
  }

  @Test
  void unknownMerchantIsNotFound() {
    assertProblem(
        postForMap("/v1/payments", paymentBody(UUID.randomUUID(), 1000, "CAD", "tok_visa_ok")),
        HttpStatus.NOT_FOUND,
        "MERCHANT_NOT_FOUND",
        "/v1/payments");
  }

  @Test
  void unknownPaymentIsNotFound() {
    String base = "/v1/payments/" + UUID.randomUUID();

    assertProblem(getForMap(base), HttpStatus.NOT_FOUND, "PAYMENT_NOT_FOUND", base);
    assertProblem(
        postForMap(base + "/capture", Map.of()),
        HttpStatus.NOT_FOUND,
        "PAYMENT_NOT_FOUND",
        base + "/capture");
    assertProblem(
        postForMap(base + "/refunds", Map.of("amount", 100)),
        HttpStatus.NOT_FOUND,
        "PAYMENT_NOT_FOUND",
        base + "/refunds");
  }

  // ---- request validation ----

  static Stream<Arguments> invalidPaymentFields() {
    return Stream.of(
        Arguments.of("amount", 0),
        Arguments.of("amount", -1),
        Arguments.of("amount", null),
        Arguments.of("currency", "cad"),
        Arguments.of("currency", "ABC"),
        Arguments.of("currency", "XAU"),
        Arguments.of("currency", null),
        Arguments.of("merchantId", null),
        Arguments.of("cardToken", null),
        Arguments.of("cardToken", ""));
  }

  @ParameterizedTest(name = "[{index}] {0} = {1}")
  @MethodSource("invalidPaymentFields")
  void rejectsInvalidPaymentRequest(String field, Object value) {
    Map<String, Object> body = paymentBody(createMerchant(), 1000, "CAD", "tok_visa_ok");
    body.put(field, value);

    assertProblem(
        postForMap("/v1/payments", body),
        HttpStatus.BAD_REQUEST,
        "VALIDATION_FAILED",
        "/v1/payments");
  }

  @ParameterizedTest
  @MethodSource("invalidRefundAmounts")
  void rejectsInvalidRefundAmount(Object amount) {
    PaymentResponse payment = capturedPayment(1000);
    String path = "/v1/payments/" + payment.id() + "/refunds";
    Map<String, Object> body = new HashMap<>();
    body.put("amount", amount);

    assertProblem(postForMap(path, body), HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", path);
    assertThat(getPayment(payment.id()).getBody().refunds()).isEmpty();
  }

  static Stream<Arguments> invalidRefundAmounts() {
    return Stream.of(Arguments.of(0), Arguments.of(-5), Arguments.of((Object) null));
  }

  @Test
  void rejectsNonUuidPaymentId() {
    assertProblem(
        getForMap("/v1/payments/not-a-uuid"),
        HttpStatus.BAD_REQUEST,
        "VALIDATION_FAILED",
        "/v1/payments/not-a-uuid");
  }

  @Test
  void rejectsMalformedJson() {
    assertProblem(
        postRawJson("/v1/payments", "{\"amount\": 10"),
        HttpStatus.BAD_REQUEST,
        "MALFORMED_REQUEST",
        "/v1/payments");
  }
}
