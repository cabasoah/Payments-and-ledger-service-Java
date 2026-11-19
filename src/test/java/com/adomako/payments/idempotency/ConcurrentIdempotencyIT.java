package com.adomako.payments.idempotency;

import static org.assertj.core.api.Assertions.assertThat;

import com.adomako.payments.AbstractApiIT;
import com.adomako.payments.payment.PaymentResponse;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 20 identical requests with the same Idempotency-Key arrive at once (a client retrying
 * aggressively). Exactly one does the work; the rest get 409 IN_PROGRESS or a replay.
 */
class ConcurrentIdempotencyIT extends AbstractApiIT {

  private static final int REQUESTS = 20;

  @Autowired JdbcClient jdbc;

  private long count(String sql, UUID id) {
    return jdbc.sql(sql).param("id", id).query(Long.class).single();
  }

  /** Every response is either {@code okStatus} (all with the same id) or 409 IN_PROGRESS. */
  private static void assertOneOutcome(
      List<ResponseEntity<Map<String, Object>>> responses, int okStatus) {
    List<ResponseEntity<Map<String, Object>>> ok = withStatus(responses, okStatus);
    List<ResponseEntity<Map<String, Object>>> inProgress = withStatus(responses, 409);

    assertThat(ok).isNotEmpty();
    assertThat(ok)
        .extracting(r -> r.getBody().get("id"))
        .containsOnly(ok.get(0).getBody().get("id"));
    assertThat(inProgress)
        .allSatisfy(
            r -> assertThat(r.getBody()).containsEntry("code", "IDEMPOTENCY_REQUEST_IN_PROGRESS"));
    assertThat(ok.size() + inProgress.size()).isEqualTo(responses.size());
  }

  @Test
  void concurrentIdenticalCreatesMakeOnePayment() throws Exception {
    UUID merchant = createMerchant();
    String key = newKey();
    Map<String, Object> body = paymentBody(merchant, 1000, "CAD", "tok_visa_ok");

    List<ResponseEntity<Map<String, Object>>> responses =
        fireTogether(REQUESTS, () -> postForMap("/v1/payments", body, key));

    assertOneOutcome(responses, 201);
    assertThat(count("SELECT count(*) FROM payments WHERE merchant_id = :id", merchant))
        .isEqualTo(1);
  }

  @Test
  void concurrentIdenticalCapturesPostOneSetOfLedgerEntries() throws Exception {
    UUID paymentId = authorizedPayment(1000).id();
    String key = newKey();
    String path = "/v1/payments/" + paymentId + "/capture";

    List<ResponseEntity<Map<String, Object>>> responses =
        fireTogether(REQUESTS, () -> postForMap(path, Map.of(), key));

    assertOneOutcome(responses, 200);
    assertThat(
            count(
                "SELECT count(*) FROM journal_transactions WHERE payment_id = :id AND kind = 'CAPTURE'",
                paymentId))
        .isEqualTo(1);
    assertThat(
            count(
                """
                SELECT count(*) FROM ledger_entries e
                JOIN journal_transactions j ON j.id = e.journal_transaction_id
                WHERE j.payment_id = :id
                """,
                paymentId))
        .isEqualTo(2);
    assertThat(getPayment(paymentId).getBody().capturedAmount()).isEqualTo(1000);
  }

  @Test
  void concurrentIdenticalRefundsRefundOnce() throws Exception {
    UUID paymentId = capturedPayment(1000).id();
    String key = newKey();
    String path = "/v1/payments/" + paymentId + "/refunds";

    List<ResponseEntity<Map<String, Object>>> responses =
        fireTogether(REQUESTS, () -> postForMap(path, Map.of("amount", 300), key));

    assertOneOutcome(responses, 201);
    PaymentResponse payment = getPayment(paymentId).getBody();
    assertThat(payment.refundedAmount()).isEqualTo(300);
    assertThat(payment.refunds()).hasSize(1);
    assertThat(
            count(
                "SELECT count(*) FROM journal_transactions WHERE payment_id = :id AND kind = 'REFUND'",
                paymentId))
        .isEqualTo(1);
  }
}
