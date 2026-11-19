package com.adomako.payments;

import static org.assertj.core.api.Assertions.assertThat;

import com.adomako.payments.ledger.BalanceCheck;
import com.adomako.payments.ledger.BalanceReport;
import com.adomako.payments.merchant.MerchantResponse;
import com.adomako.payments.payment.PaymentResponse;
import com.adomako.payments.payment.RefundResponse;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Base class for HTTP-level tests. Requests are served on server threads, outside any test
 * transaction, so these tests commit. They get their own Postgres container (and so their own
 * Spring context) to keep committed data away from the rolled-back tests in {@link AbstractIT}.
 *
 * <p>After every test the whole ledger must still balance.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class AbstractApiIT {

  @ServiceConnection
  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16");

  static {
    POSTGRES.start();
  }

  protected static final ParameterizedTypeReference<Map<String, Object>> JSON_MAP =
      new ParameterizedTypeReference<>() {};

  @LocalServerPort int port;
  @Autowired BalanceCheck balanceCheck;

  protected RestClient http;

  @BeforeEach
  void createClient() {
    http =
        RestClient.builder()
            .baseUrl("http://localhost:" + port)
            .defaultStatusHandler(status -> true, (request, response) -> {})
            .build();
  }

  @AfterEach
  void ledgerStillBalances() {
    BalanceReport report = balanceCheck.run();

    assertThat(report.isBalanced()).as("BalanceCheck after scenario: %s", report).isTrue();
  }

  // ---- requests ----

  protected static final String IDEMPOTENCY_KEY = "Idempotency-Key";

  /** A fresh key: each call is a new intent unless a test passes the same key on purpose. */
  protected static String newKey() {
    return UUID.randomUUID().toString();
  }

  protected ResponseEntity<Map<String, Object>> postForMap(String path, Object body) {
    return postForMap(path, body, newKey());
  }

  /** POST with the given Idempotency-Key; {@code null} sends no header at all. */
  protected ResponseEntity<Map<String, Object>> postForMap(
      String path, Object body, String idempotencyKey) {
    return http.post()
        .uri(path)
        .contentType(MediaType.APPLICATION_JSON)
        .headers(h -> setKey(h, idempotencyKey))
        .body(body)
        .retrieve()
        .toEntity(JSON_MAP);
  }

  protected ResponseEntity<Map<String, Object>> postRawJson(String path, String json) {
    return postRawJson(path, json, newKey());
  }

  protected ResponseEntity<Map<String, Object>> postRawJson(
      String path, String json, String idempotencyKey) {
    return http.post()
        .uri(path)
        .contentType(MediaType.APPLICATION_JSON)
        .headers(h -> setKey(h, idempotencyKey))
        .body(json)
        .retrieve()
        .toEntity(JSON_MAP);
  }

  private static void setKey(HttpHeaders headers, String idempotencyKey) {
    if (idempotencyKey != null) {
      headers.set(IDEMPOTENCY_KEY, idempotencyKey);
    }
  }

  protected ResponseEntity<Map<String, Object>> getForMap(String path) {
    return http.get().uri(path).retrieve().toEntity(JSON_MAP);
  }

  protected UUID createMerchant() {
    ResponseEntity<MerchantResponse> response =
        http.post()
            .uri("/v1/merchants")
            .contentType(MediaType.APPLICATION_JSON)
            .body(Map.of("name", "Test Merchant"))
            .retrieve()
            .toEntity(MerchantResponse.class);
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    return response.getBody().id();
  }

  protected static Map<String, Object> paymentBody(
      UUID merchantId, long amount, String currency, String cardToken) {
    Map<String, Object> body = new HashMap<>();
    body.put("merchantId", merchantId);
    body.put("amount", amount);
    body.put("currency", currency);
    body.put("cardToken", cardToken);
    return body;
  }

  protected ResponseEntity<PaymentResponse> createPayment(
      UUID merchantId, long amount, String currency, String cardToken) {
    return createPayment(merchantId, amount, currency, cardToken, newKey());
  }

  protected ResponseEntity<PaymentResponse> createPayment(
      UUID merchantId, long amount, String currency, String cardToken, String idempotencyKey) {
    return http.post()
        .uri("/v1/payments")
        .contentType(MediaType.APPLICATION_JSON)
        .headers(h -> setKey(h, idempotencyKey))
        .body(paymentBody(merchantId, amount, currency, cardToken))
        .retrieve()
        .toEntity(PaymentResponse.class);
  }

  /** An AUTHORIZED payment of the given CAD amount. */
  protected PaymentResponse authorizedPayment(long amount) {
    ResponseEntity<PaymentResponse> response =
        createPayment(createMerchant(), amount, "CAD", "tok_visa_ok");
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    return response.getBody();
  }

  /** A CAPTURED payment of the given CAD amount. */
  protected PaymentResponse capturedPayment(long amount) {
    PaymentResponse payment = authorizedPayment(amount);
    ResponseEntity<PaymentResponse> captured = capture(payment.id());
    assertThat(captured.getStatusCode()).isEqualTo(HttpStatus.OK);
    return captured.getBody();
  }

  protected ResponseEntity<PaymentResponse> capture(UUID paymentId) {
    return capture(paymentId, newKey());
  }

  protected ResponseEntity<PaymentResponse> capture(UUID paymentId, String idempotencyKey) {
    return http.post()
        .uri("/v1/payments/{id}/capture", paymentId)
        .headers(h -> setKey(h, idempotencyKey))
        .retrieve()
        .toEntity(PaymentResponse.class);
  }

  protected ResponseEntity<RefundResponse> refund(UUID paymentId, long amount) {
    return refund(paymentId, amount, newKey());
  }

  protected ResponseEntity<RefundResponse> refund(
      UUID paymentId, long amount, String idempotencyKey) {
    return http.post()
        .uri("/v1/payments/{id}/refunds", paymentId)
        .contentType(MediaType.APPLICATION_JSON)
        .headers(h -> setKey(h, idempotencyKey))
        .body(Map.of("amount", amount))
        .retrieve()
        .toEntity(RefundResponse.class);
  }

  protected ResponseEntity<PaymentResponse> getPayment(UUID paymentId) {
    return http.get()
        .uri("/v1/payments/{id}", paymentId)
        .retrieve()
        .toEntity(PaymentResponse.class);
  }

  // ---- concurrency ----

  /** Releases {@code count} copies of {@code request} at the same moment; returns the responses. */
  protected static List<ResponseEntity<Map<String, Object>>> fireTogether(
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

  protected static List<ResponseEntity<Map<String, Object>>> withStatus(
      List<ResponseEntity<Map<String, Object>>> responses, int status) {
    return responses.stream().filter(r -> r.getStatusCode().value() == status).toList();
  }

  // ---- assertions ----

  /** Asserts an RFC 7807 problem response with our error code. */
  protected static void assertProblem(
      ResponseEntity<Map<String, Object>> response,
      HttpStatus status,
      String code,
      String instance) {
    assertThat(response.getStatusCode()).isEqualTo(status);
    assertThat(response.getHeaders().getContentType())
        .isNotNull()
        .satisfies(
            type -> assertThat(type.isCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)).isTrue());
    assertThat(response.getBody())
        .containsEntry("status", status.value())
        .containsEntry("code", code)
        .containsEntry("instance", instance)
        .containsKeys("type", "title", "detail");
  }
}
