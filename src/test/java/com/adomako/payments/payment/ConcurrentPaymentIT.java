package com.adomako.payments.payment;

import static org.assertj.core.api.Assertions.assertThat;

import com.adomako.payments.AbstractApiIT;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

/**
 * Many requests hit the same payment at the same moment. Refund totals must never exceed the
 * captured amount, and a payment is captured exactly once.
 */
class ConcurrentPaymentIT extends AbstractApiIT {

  /** Releases {@code count} copies of {@code request} at the same moment; returns the responses. */
  private static List<ResponseEntity<Map<String, Object>>> fireTogether(
      int count, Callable<ResponseEntity<Map<String, Object>>> request) throws Exception {
    ExecutorService pool = Executors.newFixedThreadPool(count);
    CountDownLatch start = new CountDownLatch(1);
    try {
      List<Future<ResponseEntity<Map<String, Object>>>> futures = new ArrayList<>();
      for (int i = 0; i < count; i++) {
        futures.add(
            pool.submit(() -> {
              start.await();
              return request.call();
            }));
      }
      start.countDown();

      List<ResponseEntity<Map<String, Object>>> responses = new ArrayList<>();
      for (Future<ResponseEntity<Map<String, Object>>> future : futures) {
        responses.add(future.get(30, TimeUnit.SECONDS));
      }
      return responses;
    } finally {
      pool.shutdownNow();
    }
  }

  private static List<ResponseEntity<Map<String, Object>>> withStatus(
      List<ResponseEntity<Map<String, Object>>> responses, int status) {
    return responses.stream().filter(r -> r.getStatusCode().value() == status).toList();
  }

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
