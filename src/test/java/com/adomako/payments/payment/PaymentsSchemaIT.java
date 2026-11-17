package com.adomako.payments.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.adomako.payments.AbstractIT;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/** Proves the money invariants in V2 are enforced by Postgres itself, not just by Java code. */
class PaymentsSchemaIT extends AbstractIT {

  @Autowired NamedParameterJdbcTemplate jdbc;

  private UUID insertMerchant(String name) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO merchants (id, name) VALUES (:id, :name)", Map.of("id", id, "name", name));
    return id;
  }

  /** A valid payment row; each override replaces one column value (null allowed). */
  private Map<String, Object> validPayment(Object... overrides) {
    Map<String, Object> row = new HashMap<>();
    row.put("merchant_id", insertMerchant("Test Merchant"));
    row.put("amount_minor", 1000L);
    row.put("currency", "CAD");
    row.put("status", "CAPTURED");
    row.put("captured_minor", 1000L);
    row.put("refunded_minor", 0L);
    row.put("processor_ref", "proc_" + UUID.randomUUID());
    for (int i = 0; i < overrides.length; i += 2) {
      row.put((String) overrides[i], overrides[i + 1]);
    }
    return row;
  }

  private void insertPayment(Map<String, Object> row) {
    jdbc.update(
        """
        INSERT INTO payments (merchant_id, amount_minor, currency, status,
                              captured_minor, refunded_minor, processor_ref)
        VALUES (:merchant_id, :amount_minor, :currency, :status,
                :captured_minor, :refunded_minor, :processor_ref)
        """,
        row);
  }

  @Test
  void acceptsValidPayment() {
    insertPayment(validPayment());
  }

  @Test
  void acceptsBoundaryAmounts() {
    insertPayment(validPayment("amount_minor", 500L, "captured_minor", 500L));
    insertPayment(
        validPayment("status", "REFUNDED", "captured_minor", 700L, "refunded_minor", 700L));
    insertPayment(validPayment("status", "PENDING", "captured_minor", 0L));
  }

  @Test
  void allowsManyPaymentsWithoutProcessorRef() {
    insertPayment(validPayment("processor_ref", null));
    insertPayment(validPayment("processor_ref", null));
  }

  @Test
  void defaultsCapturedRefundedAndVersionToZero() {
    UUID merchantId = insertMerchant("Defaults Merchant");
    UUID paymentId = UUID.randomUUID();
    jdbc.update(
        """
        INSERT INTO payments (id, merchant_id, amount_minor, currency, status)
        VALUES (:id, :merchant_id, 1000, 'CAD', 'PENDING')
        """,
        Map.of("id", paymentId, "merchant_id", merchantId));

    Map<String, Object> row =
        jdbc.queryForMap(
            "SELECT captured_minor, refunded_minor, version FROM payments WHERE id = :id",
            Map.of("id", paymentId));

    assertThat(row)
        .containsEntry("captured_minor", 0L)
        .containsEntry("refunded_minor", 0L)
        .containsEntry("version", 0L);
  }

  static Stream<Arguments> constraintViolations() {
    return Stream.of(
        Arguments.of(
            "amount zero",
            "payments_amount_positive",
            over("amount_minor", 0L, "captured_minor", 0L)),
        Arguments.of(
            "amount negative",
            "payments_amount_positive",
            over("amount_minor", -1L, "captured_minor", 0L)),
        Arguments.of("captured negative", "payments_captured_range", over("captured_minor", -1L)),
        Arguments.of("captured > amount", "payments_captured_range", over("captured_minor", 1001L)),
        Arguments.of("refunded negative", "payments_refunded_range", over("refunded_minor", -1L)),
        Arguments.of(
            "refunded > captured",
            "payments_refunded_range",
            over("captured_minor", 500L, "refunded_minor", 501L)),
        Arguments.of("unknown status", "payments_status_valid", over("status", "VOIDED")),
        Arguments.of("lowercase status", "payments_status_valid", over("status", "pending")),
        Arguments.of("lowercase currency", "payments_currency_format", over("currency", "cad")),
        Arguments.of("non-letter currency", "payments_currency_format", over("currency", "CA1")),
        Arguments.of(
            "unknown merchant", "payments_merchant_fk", over("merchant_id", UUID.randomUUID())));
  }

  private static Object[] over(Object... overrides) {
    return overrides;
  }

  private static Arguments amounts(String status, long captured, long refunded) {
    return Arguments.of(status, captured, refunded);
  }

  static Stream<Arguments> statusAmountsThatMatch() {
    return Stream.of(
        amounts("PENDING", 0, 0),
        amounts("AUTHORIZED", 0, 0),
        amounts("FAILED", 0, 0),
        amounts("CAPTURED", 1000, 0),
        amounts("CAPTURED", 600, 0),
        amounts("PARTIALLY_REFUNDED", 1000, 300),
        amounts("REFUNDED", 1000, 1000),
        amounts("REFUNDED", 600, 600));
  }

  static Stream<Arguments> statusAmountsThatDoNotMatch() {
    return Stream.of(
        amounts("PENDING", 100, 0),
        amounts("AUTHORIZED", 100, 0),
        amounts("FAILED", 100, 0),
        amounts("CAPTURED", 0, 0),
        amounts("CAPTURED", 1000, 300),
        amounts("PARTIALLY_REFUNDED", 1000, 0),
        amounts("PARTIALLY_REFUNDED", 1000, 1000),
        amounts("REFUNDED", 1000, 300),
        amounts("REFUNDED", 0, 0));
  }

  @ParameterizedTest(name = "[{index}] {0} captured={1} refunded={2}")
  @MethodSource("statusAmountsThatMatch")
  void acceptsStatusMatchingAmounts(String status, long captured, long refunded) {
    insertPayment(
        validPayment("status", status, "captured_minor", captured, "refunded_minor", refunded));
  }

  @ParameterizedTest(name = "[{index}] {0} captured={1} refunded={2}")
  @MethodSource("statusAmountsThatDoNotMatch")
  void rejectsStatusNotMatchingAmounts(String status, long captured, long refunded) {
    Map<String, Object> row =
        validPayment("status", status, "captured_minor", captured, "refunded_minor", refunded);

    assertThatThrownBy(() -> insertPayment(row))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("payments_status_matches_amounts");
  }

  @ParameterizedTest(name = "[{index}] {0} -> {1}")
  @MethodSource("constraintViolations")
  void rejectsConstraintViolation(String description, String constraint, Object[] overrides) {
    Map<String, Object> row = validPayment(overrides);

    assertThatThrownBy(() -> insertPayment(row))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining(constraint);
  }

  @Test
  void rejectsDuplicateProcessorRef() {
    String duplicate = "proc_dup_" + UUID.randomUUID();
    insertPayment(validPayment("processor_ref", duplicate));

    assertThatThrownBy(() -> insertPayment(validPayment("processor_ref", duplicate)))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("payments_processor_ref_unique");
  }

  @ParameterizedTest
  @ValueSource(strings = {"merchant_id", "amount_minor", "currency", "status"})
  void rejectsNullInRequiredColumn(String column) {
    Map<String, Object> row = validPayment(column, null);

    assertThatThrownBy(() -> insertPayment(row))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("null value in column \"" + column + "\"");
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "   "})
  void rejectsBlankMerchantName(String name) {
    assertThatThrownBy(() -> insertMerchant(name))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("merchants_name_not_blank");
  }
}
