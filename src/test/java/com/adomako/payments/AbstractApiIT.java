package com.adomako.payments;

import static org.assertj.core.api.Assertions.assertThat;

import com.adomako.payments.ledger.BalanceCheck;
import com.adomako.payments.ledger.BalanceReport;
import com.adomako.payments.merchant.MerchantResponse;
import com.adomako.payments.payment.PaymentResponse;
import com.adomako.payments.payment.RefundResponse;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.core.ParameterizedTypeReference;
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

  protected ResponseEntity<Map<String, Object>> postForMap(String path, Object body) {
    return http.post()
        .uri(path)
        .contentType(MediaType.APPLICATION_JSON)
        .body(body)
        .retrieve()
        .toEntity(JSON_MAP);
  }

  protected ResponseEntity<Map<String, Object>> postRawJson(String path, String json) {
    return http.post()
        .uri(path)
        .contentType(MediaType.APPLICATION_JSON)
        .body(json)
        .retrieve()
        .toEntity(JSON_MAP);
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
    return http.post()
        .uri("/v1/payments")
        .contentType(MediaType.APPLICATION_JSON)
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
    return http.post()
        .uri("/v1/payments/{id}/capture", paymentId)
        .retrieve()
        .toEntity(PaymentResponse.class);
  }

  protected ResponseEntity<RefundResponse> refund(UUID paymentId, long amount) {
    return http.post()
        .uri("/v1/payments/{id}/refunds", paymentId)
        .contentType(MediaType.APPLICATION_JSON)
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
