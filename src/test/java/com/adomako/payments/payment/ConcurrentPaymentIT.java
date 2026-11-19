package com.adomako.payments.payment;

import static org.assertj.core.api.Assertions.assertThat;

import com.adomako.payments.AbstractApiIT;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

/**
 * Many requests hit the same payment at the same moment. Refund totals must never exceed the
 * captured amount, and a payment is captured exactly once.
 *
 * <p>Each request here has its own Idempotency-Key: these are different intents, not retries.
 */
class ConcurrentPaymentIT extends AbstractApiIT {

  @Test
  void concurrentRefundsNeverExceedCaptured() throws Exception {
    UUID paymentId = capturedPayment(1000).id();
    String path = "/v1/payments/" + paymentId + "/refunds";

    List<ResponseEntity<Map<String, Object>>> responses =
        fireTogether(10, () -> postForMap(path, Map.of("amount", 300)));

    assertThat(withStatus(responses, 201)).hasSize(3);
    assertThat(withStatus(responses, 422))
        .hasSize(7)
        .allSatisfy(r -> assertThat(r.getBody()).containsEntry("code", "REFUND_EXCEEDS_CAPTURED"));

    PaymentResponse payment = getPayment(paymentId).getBody();
    assertThat(payment.refundedAmount()).isEqualTo(900);
    assertThat(payment.refunds()).hasSize(3);
    assertThat(payment.status()).isEqualTo(PaymentStatus.PARTIALLY_REFUNDED);
  }

  @Test
  void concurrentCapturesCaptureExactlyOnce() throws Exception {
    UUID paymentId = authorizedPayment(1000).id();
    String path = "/v1/payments/" + paymentId + "/capture";

    List<ResponseEntity<Map<String, Object>>> responses =
        fireTogether(5, () -> postForMap(path, Map.of()));

    assertThat(withStatus(responses, 200)).hasSize(1);
    assertThat(withStatus(responses, 409))
        .hasSize(4)
        .allSatisfy(r -> assertThat(r.getBody()).containsEntry("code", "INVALID_STATE_TRANSITION"));
    assertThat(getPayment(paymentId).getBody().capturedAmount()).isEqualTo(1000);
  }
}
