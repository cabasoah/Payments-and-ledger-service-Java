package com.adomako.payments.idempotency;

import static org.assertj.core.api.Assertions.assertThat;

import com.adomako.payments.AbstractApiIT;
import com.adomako.payments.payment.PaymentResponse;
import com.adomako.payments.payment.PaymentStatus;
import com.adomako.payments.payment.RefundResponse;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Retrying a POST with the same Idempotency-Key never charges or refunds twice. */
class IdempotencyApiIT extends AbstractApiIT {

  private static final String REPLAYED = "Idempotent-Replayed";

  @Autowired JdbcClient jdbc;

  // ---- database counts (API tests commit, so the test thread sees the server's writes) ----

  private long paymentsFor(UUID merchantId) {
    return jdbc.sql("SELECT count(*) FROM payments WHERE merchant_id = :id")
        .param("id", merchantId)
        .query(Long.class)
        .single();
  }

  private long journals(UUID paymentId, String kind) {
    return jdbc.sql(
            "SELECT count(*) FROM journal_transactions WHERE payment_id = :id AND kind = :kind")
        .param("id", paymentId)
        .param("kind", kind)
        .query(Long.class)
        .single();
  }

  private long ledgerEntries(UUID paymentId) {
    return jdbc.sql(
            """
            SELECT count(*) FROM ledger_entries e
            JOIN journal_transactions j ON j.id = e.journal_transaction_id
            WHERE j.payment_id = :id
            """)
        .param("id", paymentId)
        .query(Long.class)
        .single();
  }

  private void expireKey(UUID merchantId, String key) {
    jdbc.sql(
            """
            UPDATE idempotency_keys
            SET created_at = now() - interval '25 hours', expires_at = now() - interval '1 hour'
            WHERE merchant_id = :merchantId AND idempotency_key = :key
            """)
        .param("merchantId", merchantId)
        .param("key", key)
        .update();
  }

  private static boolean replayed(ResponseEntity<?> response) {
    return "true".equals(response.getHeaders().getFirst(REPLAYED));
  }

  // ---- missing or invalid key ----

  @Test
  void createWithoutKeyIsRejected() {
    UUID merchant = createMerchant();

    assertProblem(
        postForMap("/v1/payments", paymentBody(merchant, 1000, "CAD", "tok_visa_ok"), null),
        HttpStatus.BAD_REQUEST,
        "IDEMPOTENCY_KEY_MISSING",
        "/v1/payments");
    assertThat(paymentsFor(merchant)).isZero();
  }

  @Test
  void captureWithoutKeyIsRejected() {
    PaymentResponse payment = authorizedPayment(1000);
    String path = "/v1/payments/" + payment.id() + "/capture";

    assertProblem(
        postForMap(path, Map.of(), null), HttpStatus.BAD_REQUEST, "IDEMPOTENCY_KEY_MISSING", path);
    assertThat(getPayment(payment.id()).getBody().status()).isEqualTo(PaymentStatus.AUTHORIZED);
  }

  @Test
  void refundWithoutKeyIsRejected() {
    PaymentResponse payment = capturedPayment(1000);
    String path = "/v1/payments/" + payment.id() + "/refunds";

    assertProblem(
        postForMap(path, Map.of("amount", 300), null),
        HttpStatus.BAD_REQUEST,
        "IDEMPOTENCY_KEY_MISSING",
        path);
    assertThat(getPayment(payment.id()).getBody().refunds()).isEmpty();
  }

  @Test
  void blankKeyIsRejected() {
    assertProblem(
        postForMap("/v1/payments", paymentBody(createMerchant(), 1000, "CAD", "tok_visa_ok"), "  "),
        HttpStatus.BAD_REQUEST,
        "IDEMPOTENCY_KEY_MISSING",
        "/v1/payments");
  }

  @Test
  void keyLongerThan255CharactersIsRejected() {
    assertProblem(
        postForMap(
            "/v1/payments",
            paymentBody(createMerchant(), 1000, "CAD", "tok_visa_ok"),
            "k".repeat(256)),
        HttpStatus.BAD_REQUEST,
        "VALIDATION_FAILED",
        "/v1/payments");
  }

  // ---- sequential duplicates replay ----

  @Test
  void duplicateCreateReplaysTheSamePayment() {
    UUID merchant = createMerchant();
    String key = newKey();

    ResponseEntity<PaymentResponse> first =
        createPayment(merchant, 1000, "CAD", "tok_visa_ok", key);
    ResponseEntity<PaymentResponse> second =
        createPayment(merchant, 1000, "CAD", "tok_visa_ok", key);

    assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(replayed(first)).isFalse();
    assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(replayed(second)).isTrue();
    assertThat(second.getBody()).isEqualTo(first.getBody());
    assertThat(second.getHeaders().getLocation()).isEqualTo(first.getHeaders().getLocation());
    assertThat(paymentsFor(merchant)).isEqualTo(1);
  }

  @Test
  void duplicateDeclinedPaymentReplaysWithoutReauthorizing() {
    UUID merchant = createMerchant();
    String key = newKey();

    PaymentResponse first = createPayment(merchant, 1000, "CAD", "tok_declined", key).getBody();
    ResponseEntity<PaymentResponse> second =
        createPayment(merchant, 1000, "CAD", "tok_declined", key);

    assertThat(first.status()).isEqualTo(PaymentStatus.FAILED);
    assertThat(replayed(second)).isTrue();
    assertThat(second.getBody().id()).isEqualTo(first.id());
    assertThat(paymentsFor(merchant)).isEqualTo(1);
  }

  @Test
  void duplicateCaptureReplaysInsteadOfConflicting() {
    UUID paymentId = authorizedPayment(1000).id();
    String key = newKey();

    ResponseEntity<PaymentResponse> first = capture(paymentId, key);
    ResponseEntity<PaymentResponse> second = capture(paymentId, key);

    assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(replayed(second)).isTrue();
    assertThat(second.getBody().capturedAmount()).isEqualTo(1000);
    assertThat(journals(paymentId, "CAPTURE")).isEqualTo(1);
    assertThat(ledgerEntries(paymentId)).isEqualTo(2);
  }

  @Test
  void duplicateRefundRefundsOnce() {
    UUID paymentId = capturedPayment(1000).id();
    String key = newKey();

    ResponseEntity<RefundResponse> first = refund(paymentId, 300, key);
    ResponseEntity<RefundResponse> second = refund(paymentId, 300, key);

    assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(replayed(second)).isTrue();
    assertThat(second.getBody().id()).isEqualTo(first.getBody().id());

    PaymentResponse payment = getPayment(paymentId).getBody();
    assertThat(payment.refundedAmount()).isEqualTo(300);
    assertThat(payment.refunds()).hasSize(1);
    assertThat(journals(paymentId, "REFUND")).isEqualTo(1);
  }

  @Test
  void sameRequestWithDifferentJsonFormattingReplays() {
    UUID merchant = createMerchant();
    String key = newKey();
    String compact =
        "{\"merchantId\":\"%s\",\"amount\":1000,\"currency\":\"CAD\",\"cardToken\":\"tok_visa_ok\"}"
            .formatted(merchant);
    String reorderedAndSpaced =
        """
        {
          "cardToken": "tok_visa_ok",
          "currency":  "CAD",
          "amount":    1000,
          "merchantId": "%s"
        }
        """
            .formatted(merchant);

    ResponseEntity<Map<String, Object>> first = postRawJson("/v1/payments", compact, key);
    ResponseEntity<Map<String, Object>> second =
        postRawJson("/v1/payments", reorderedAndSpaced, key);

    assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(replayed(second)).isTrue();
    assertThat(second.getBody().get("id")).isEqualTo(first.getBody().get("id"));
    assertThat(paymentsFor(merchant)).isEqualTo(1);
  }

  // ---- key reused for a different request ----

  @Test
  void sameKeyWithDifferentBodyIsRejected() {
    UUID merchant = createMerchant();
    String key = newKey();
    createPayment(merchant, 1000, "CAD", "tok_visa_ok", key);

    assertProblem(
        postForMap("/v1/payments", paymentBody(merchant, 2000, "CAD", "tok_visa_ok"), key),
        HttpStatus.UNPROCESSABLE_CONTENT,
        "IDEMPOTENCY_KEY_REUSED",
        "/v1/payments");
    assertThat(paymentsFor(merchant)).isEqualTo(1);
  }

  @Test
  void sameKeyOnAnotherPaymentIsRejected() {
    UUID merchant = createMerchant();
    UUID paymentA = createPayment(merchant, 1000, "CAD", "tok_visa_ok").getBody().id();
    UUID paymentB = createPayment(merchant, 1000, "CAD", "tok_visa_ok").getBody().id();
    capture(paymentA);
    capture(paymentB);
    String key = newKey();
    refund(paymentA, 300, key);
    String pathB = "/v1/payments/" + paymentB + "/refunds";

    assertProblem(
        postForMap(pathB, Map.of("amount", 300), key),
        HttpStatus.UNPROCESSABLE_CONTENT,
        "IDEMPOTENCY_KEY_REUSED",
        pathB);
    assertThat(getPayment(paymentB).getBody().refunds()).isEmpty();
  }

  @Test
  void keysAreScopedPerMerchant() {
    UUID merchantA = createMerchant();
    UUID merchantB = createMerchant();
    String key = newKey();

    ResponseEntity<PaymentResponse> a = createPayment(merchantA, 1000, "CAD", "tok_visa_ok", key);
    ResponseEntity<PaymentResponse> b = createPayment(merchantB, 1000, "CAD", "tok_visa_ok", key);

    assertThat(replayed(b)).isFalse();
    assertThat(b.getBody().id()).isNotEqualTo(a.getBody().id());
    assertThat(paymentsFor(merchantA)).isEqualTo(1);
    assertThat(paymentsFor(merchantB)).isEqualTo(1);
  }

  // ---- expiry ----

  @Test
  void expiredKeyRunsTheRequestAgain() {
    UUID merchant = createMerchant();
    String key = newKey();
    PaymentResponse first = createPayment(merchant, 1000, "CAD", "tok_visa_ok", key).getBody();
    expireKey(merchant, key);

    ResponseEntity<PaymentResponse> second =
        createPayment(merchant, 1000, "CAD", "tok_visa_ok", key);

    assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(replayed(second)).isFalse();
    assertThat(second.getBody().id()).isNotEqualTo(first.id());
    assertThat(paymentsFor(merchant)).isEqualTo(2);
  }

  // ---- failures are not stored ----

  @Test
  void failedRequestDoesNotUseUpTheKey() {
    UUID paymentId = capturedPayment(1000).id();
    String key = newKey();
    String path = "/v1/payments/" + paymentId + "/refunds";

    assertProblem(
        postForMap(path, Map.of("amount", 1001), key),
        HttpStatus.UNPROCESSABLE_CONTENT,
        "REFUND_EXCEEDS_CAPTURED",
        path);
    ResponseEntity<RefundResponse> retry = refund(paymentId, 300, key);

    assertThat(retry.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(replayed(retry)).isFalse();
    assertThat(getPayment(paymentId).getBody().refundedAmount()).isEqualTo(300);
  }
}
