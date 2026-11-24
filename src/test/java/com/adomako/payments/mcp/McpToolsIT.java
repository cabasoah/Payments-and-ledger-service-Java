package com.adomako.payments.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import com.adomako.payments.AbstractApiIT;
import com.adomako.payments.payment.PaymentResponse;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * The MCP tools end to end against the real database, called through the same handlers the MCP
 * server uses (runner, audit log, error mapping). Write tools are enabled here.
 */
@TestPropertySource(properties = "payments.mcp.read-only=false")
class McpToolsIT extends AbstractApiIT {

  @Autowired McpToolRegistry registry;
  @Autowired JsonMapper json;
  @Autowired JdbcClient jdbc;

  /** A tool call's outcome: the JSON body, and whether it was an error result. */
  record Outcome(boolean isError, Map<String, Object> body) {

    @SuppressWarnings("unchecked")
    Map<String, Object> map(String key) {
      return (Map<String, Object>) body.get(key);
    }

    String errorCode() {
      return (String) map("error").get("code");
    }
  }

  private Outcome call(String tool, Map<String, Object> arguments) {
    SyncToolSpecification spec =
        registry.specifications().stream()
            .filter(s -> s.tool().name().equals(tool))
            .findFirst()
            .orElseThrow(() -> new AssertionError("tool not registered: " + tool));
    CallToolResult result =
        spec.callHandler().apply(null, new CallToolRequest(tool, arguments, null));
    String text = ((TextContent) result.content().getFirst()).text();
    return new Outcome(
        result.isError(), json.readValue(text, new TypeReference<Map<String, Object>>() {}));
  }

  private static Map<String, Object> createArgs(UUID merchant, long amount, String key) {
    Map<String, Object> args = new HashMap<>();
    args.put("merchantId", merchant.toString());
    args.put("amount", amount);
    args.put("currency", "CAD");
    args.put("cardToken", "tok_visa_ok");
    args.put("idempotencyKey", key);
    return args;
  }

  private long paymentCount(UUID merchant) {
    return jdbc.sql("SELECT count(*) FROM payments WHERE merchant_id = :merchant")
        .param("merchant", merchant)
        .query(Long.class)
        .single();
  }

  private long journalCount(UUID payment) {
    return jdbc.sql("SELECT count(*) FROM journal_transactions WHERE payment_id = :payment")
        .param("payment", payment)
        .query(Long.class)
        .single();
  }

  private static Map<String, Object> money(int amount, String display) {
    return Map.of("amount", amount, "currency", "CAD", "display", display);
  }

  private String createdPaymentId(UUID merchant, long amount) {
    Outcome created = call("create_payment", createArgs(merchant, amount, newKey()));
    assertThat(created.isError()).as("create: %s", created.body()).isFalse();
    return (String) created.map("payment").get("id");
  }

  private String capturedPaymentId(long amount) {
    String id = createdPaymentId(createMerchant(), amount);
    Outcome captured = call("capture_payment", Map.of("paymentId", id, "idempotencyKey", newKey()));
    assertThat(captured.isError()).as("capture: %s", captured.body()).isFalse();
    return id;
  }

  @Test
  void allSixToolsAreOfferedWhenWritesAreEnabled() {
    assertThat(registry.specifications())
        .extracting(s -> s.tool().name())
        .containsExactly(
            "get_payment",
            "list_payments",
            "get_ledger_balance",
            "create_payment",
            "capture_payment",
            "refund_payment");
  }

  // ---- idempotency ----

  @Test
  void retriedCreateWithTheSameKeyCreatesOnePayment() {
    UUID merchant = createMerchant();
    String key = newKey();

    Outcome first = call("create_payment", createArgs(merchant, 1050, key));
    Outcome retry = call("create_payment", createArgs(merchant, 1050, key));

    assertThat(first.isError()).isFalse();
    assertThat(retry.isError()).isFalse();
    assertThat(first.body()).containsEntry("replayed", false);
    assertThat(retry.body()).containsEntry("replayed", true);
    assertThat(retry.map("payment").get("id")).isEqualTo(first.map("payment").get("id"));
    assertThat(paymentCount(merchant)).isEqualTo(1);
  }

  @Test
  void concurrentRetriesWithTheSameKeyCreateOnePayment() throws Exception {
    UUID merchant = createMerchant();
    String key = newKey();
    int callers = 10;
    ExecutorService pool = Executors.newFixedThreadPool(callers);
    CountDownLatch start = new CountDownLatch(1);
    List<Future<Outcome>> futures = new ArrayList<>();
    try {
      for (int i = 0; i < callers; i++) {
        Callable<Outcome> task =
            () -> {
              start.await();
              return call("create_payment", createArgs(merchant, 1050, key));
            };
        futures.add(pool.submit(task));
      }
      start.countDown();

      List<Outcome> outcomes = new ArrayList<>();
      for (Future<Outcome> f : futures) {
        outcomes.add(f.get(30, TimeUnit.SECONDS));
      }

      assertThat(paymentCount(merchant)).isEqualTo(1);
      assertThat(outcomes)
          .allSatisfy(o -> {
            if (o.isError()) {
              assertThat(o.errorCode()).isEqualTo("IDEMPOTENCY_REQUEST_IN_PROGRESS");
            }
          });
      Set<Object> paymentIds =
          outcomes.stream()
              .filter(o -> !o.isError())
              .map(o -> o.map("payment").get("id"))
              .collect(Collectors.toSet());
      assertThat(paymentIds).hasSize(1);
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void sameKeyForADifferentPaymentIsRejected() {
    UUID merchant = createMerchant();
    String key = newKey();
    call("create_payment", createArgs(merchant, 1050, key));

    Outcome different = call("create_payment", createArgs(merchant, 2000, key));

    assertThat(different.isError()).isTrue();
    assertThat(different.errorCode()).isEqualTo("IDEMPOTENCY_KEY_REUSED");
    assertThat(paymentCount(merchant)).isEqualTo(1);
  }

  @Test
  void aKeyUsedOverRestIsReplayedOverMcp() {
    UUID merchant = createMerchant();
    String key = newKey();
    var rest = createPayment(merchant, 1050, "CAD", "tok_visa_ok", key);
    assertThat(rest.getStatusCode()).isEqualTo(HttpStatus.CREATED);

    Outcome mcp = call("create_payment", createArgs(merchant, 1050, key));

    assertThat(mcp.isError()).isFalse();
    assertThat(mcp.body()).containsEntry("replayed", true);
    assertThat(mcp.map("payment").get("id")).isEqualTo(rest.getBody().id().toString());
    assertThat(paymentCount(merchant)).isEqualTo(1);
  }

  // ---- refunds and the ledger ----

  @Test
  void createCaptureRefundThenTheLedgerBalances() {
    String id = capturedPaymentId(1050);

    Outcome refund =
        call("refund_payment", Map.of("paymentId", id, "amount", 300, "idempotencyKey", newKey()));
    Outcome ledger = call("get_ledger_balance", Map.of("paymentId", id));

    assertThat(refund.isError()).as("refund: %s", refund.body()).isFalse();
    assertThat(refund.map("refund")).containsEntry("amount", money(300, "3.00 CAD"));
    assertThat(refund.map("payment"))
        .containsEntry("status", "PARTIALLY_REFUNDED")
        .containsEntry("refunded", money(300, "3.00 CAD"))
        .containsEntry("refundable", money(750, "7.50 CAD"));

    assertThat(ledger.isError()).isFalse();
    assertThat(ledger.body())
        .containsEntry("paymentId", id)
        .containsEntry("balanced", true)
        .containsEntry("matchesPayment", true)
        .containsEntry("merchantPayableBalance", money(750, "7.50 CAD"))
        .containsEntry("expectedMerchantPayableBalance", money(750, "7.50 CAD"));
    assertThat((List<?>) ledger.body().get("entries")).hasSize(4);
    assertThat((List<Object>) ledger.body().get("totals"))
        .containsExactly(
            Map.of(
                "currency",
                "CAD",
                "debits",
                money(1350, "13.50 CAD"),
                "credits",
                money(1350, "13.50 CAD"),
                "balanced",
                true));
  }

  @Test
  void overRefundIsRejectedAndChangesNothing() {
    String id = capturedPaymentId(1000);

    Outcome over =
        call("refund_payment", Map.of("paymentId", id, "amount", 1001, "idempotencyKey", newKey()));

    assertThat(over.isError()).isTrue();
    assertThat(over.errorCode()).isEqualTo("REFUND_EXCEEDS_CAPTURED");
    assertThat(over.map("error"))
        .containsEntry("detail", "Refund of 10.01 CAD exceeds the remaining refundable 10.00 CAD")
        .containsKey("hint");

    Outcome payment = call("get_payment", Map.of("paymentId", id));
    assertThat(payment.body())
        .containsEntry("status", "CAPTURED")
        .containsEntry("refunded", money(0, "0.00 CAD"));
    assertThat(journalCount(UUID.fromString(id))).isEqualTo(1);
  }

  @Test
  void refundOverWhatRemainsIsRejected() {
    String id = capturedPaymentId(1000);
    call("refund_payment", Map.of("paymentId", id, "amount", 700, "idempotencyKey", newKey()));

    Outcome over =
        call("refund_payment", Map.of("paymentId", id, "amount", 301, "idempotencyKey", newKey()));

    assertThat(over.errorCode()).isEqualTo("REFUND_EXCEEDS_CAPTURED");
    assertThat(over.map("error"))
        .containsEntry("detail", "Refund of 3.01 CAD exceeds the remaining refundable 3.00 CAD");
  }

  @Test
  void refundBeforeCaptureIsAnInvalidTransition() {
    String id = createdPaymentId(createMerchant(), 1000);

    Outcome early =
        call("refund_payment", Map.of("paymentId", id, "amount", 100, "idempotencyKey", newKey()));

    assertThat(early.errorCode()).isEqualTo("INVALID_STATE_TRANSITION");
  }

  // ---- reads ----

  @Test
  void getPaymentReturnsAmountsWithCurrency() {
    String id = createdPaymentId(createMerchant(), 1050);

    Outcome payment = call("get_payment", Map.of("paymentId", id));

    assertThat(payment.isError()).isFalse();
    assertThat(payment.body())
        .containsEntry("id", id)
        .containsEntry("status", "AUTHORIZED")
        .containsEntry("amount", money(1050, "10.50 CAD"))
        .containsEntry("captured", money(0, "0.00 CAD"))
        .containsEntry("refundable", money(0, "0.00 CAD"));
  }

  @Test
  void listPaymentsNewestFirst() {
    UUID merchant = createMerchant();
    String first = createdPaymentId(merchant, 100);
    String second = createdPaymentId(merchant, 200);

    Outcome list = call("list_payments", Map.of("merchantId", merchant.toString()));

    assertThat(list.isError()).isFalse();
    assertThat(list.body()).containsEntry("count", 2);
    assertThat((List<Map<String, Object>>) list.body().get("payments"))
        .extracting(p -> p.get("id"))
        .containsExactly(second, first);
  }

  @Test
  void listPaymentsRespectsTheLimit() {
    UUID merchant = createMerchant();
    createdPaymentId(merchant, 100);
    String newest = createdPaymentId(merchant, 200);

    Outcome list = call("list_payments", Map.of("merchantId", merchant.toString(), "limit", 1));

    assertThat(list.body()).containsEntry("count", 1);
    assertThat((List<Map<String, Object>>) list.body().get("payments"))
        .extracting(p -> p.get("id"))
        .containsExactly(newest);
  }

  @Test
  void listPaymentsLimitOutOfRangeIsRejected() {
    Outcome list =
        call("list_payments", Map.of("merchantId", createMerchant().toString(), "limit", 101));

    assertThat(list.errorCode()).isEqualTo("VALIDATION_FAILED");
  }

  @Test
  void unknownIdsAreNotFound() {
    String unknown = UUID.randomUUID().toString();

    assertThat(call("get_payment", Map.of("paymentId", unknown)).errorCode())
        .isEqualTo("PAYMENT_NOT_FOUND");
    assertThat(call("get_ledger_balance", Map.of("paymentId", unknown)).errorCode())
        .isEqualTo("PAYMENT_NOT_FOUND");
    assertThat(call("list_payments", Map.of("merchantId", unknown)).errorCode())
        .isEqualTo("MERCHANT_NOT_FOUND");
  }

  @Test
  void restCreatedPaymentIsVisibleThroughMcp() {
    PaymentResponse rest = capturedPayment(500);

    Outcome ledger = call("get_ledger_balance", Map.of("paymentId", rest.id().toString()));

    assertThat(ledger.body())
        .containsEntry("balanced", true)
        .containsEntry("matchesPayment", true)
        .containsEntry("merchantPayableBalance", money(500, "5.00 CAD"));
  }
}
