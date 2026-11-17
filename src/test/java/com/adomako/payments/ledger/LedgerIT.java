package com.adomako.payments.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.adomako.payments.AbstractIT;
import com.adomako.payments.common.Money;
import java.util.Currency;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;

class LedgerIT extends AbstractIT {

  private static final Currency CAD = Currency.getInstance("CAD");
  private static final Currency USD = Currency.getInstance("USD");

  @Autowired LedgerService ledger;
  @Autowired LedgerRepository repository;
  @Autowired JdbcClient jdbc;

  private TestRows rows;

  record EntryRow(
      String accountType, UUID merchantId, String direction, long amount, String currency) {}

  @BeforeEach
  void setUp() {
    rows = new TestRows(jdbc);
  }

  private List<EntryRow> entries(UUID paymentId, String kind) {
    return jdbc.sql(
            """
            SELECT a.type, a.merchant_id, e.direction, e.amount_minor, e.currency
            FROM ledger_entries e
            JOIN ledger_accounts a ON a.id = e.account_id
            JOIN journal_transactions j ON j.id = e.journal_transaction_id
            WHERE j.payment_id = :paymentId AND j.kind = :kind
            """)
        .param("paymentId", paymentId)
        .param("kind", kind)
        .query((rs, n) ->
            new EntryRow(
                rs.getString("type"),
                rs.getObject("merchant_id", UUID.class),
                rs.getString("direction"),
                rs.getLong("amount_minor"),
                rs.getString("currency")))
        .list();
  }

  private long journalCount(UUID paymentId, String kind) {
    return jdbc.sql(
            "SELECT count(*) FROM journal_transactions WHERE payment_id = :paymentId AND kind = :kind")
        .param("paymentId", paymentId)
        .param("kind", kind)
        .query(Long.class)
        .single();
  }

  private long merchantPayableBalance(UUID merchantId) {
    return jdbc.sql(
            """
            SELECT COALESCE(SUM(CASE e.direction WHEN 'CREDIT' THEN e.amount_minor
                                                 ELSE -e.amount_minor END), 0)::bigint
            FROM ledger_entries e
            JOIN ledger_accounts a ON a.id = e.account_id
            WHERE a.type = 'MERCHANT_PAYABLE' AND a.merchant_id = :merchantId
              AND a.currency = 'CAD'
            """)
        .param("merchantId", merchantId)
        .query(Long.class)
        .single();
  }

  private long count(String table) {
    return jdbc.sql("SELECT count(*) FROM " + table).query(Long.class).single();
  }

  @Test
  void captureDebitsProcessorReceivableAndCreditsMerchantPayable() {
    UUID merchant = rows.merchant();
    UUID payment = rows.payment(merchant, "CAPTURED", 1000, 0);

    ledger.recordCapture(payment, merchant, Money.of(1000, CAD));

    assertThat(journalCount(payment, "CAPTURE")).isEqualTo(1);
    assertThat(entries(payment, "CAPTURE"))
        .containsExactlyInAnyOrder(
            new EntryRow("PROCESSOR_RECEIVABLE", null, "DEBIT", 1000, "CAD"),
            new EntryRow("MERCHANT_PAYABLE", merchant, "CREDIT", 1000, "CAD"));
    assertThat(merchantPayableBalance(merchant)).isEqualTo(1000);
  }

  @Test
  void partialRefundDebitsMerchantPayableAndCreditsProcessorReceivable() {
    UUID merchant = rows.merchant();
    UUID payment = rows.payment(merchant, "PARTIALLY_REFUNDED", 1000, 300);
    ledger.recordCapture(payment, merchant, Money.of(1000, CAD));

    ledger.recordRefund(payment, merchant, Money.of(300, CAD));

    assertThat(journalCount(payment, "REFUND")).isEqualTo(1);
    assertThat(entries(payment, "REFUND"))
        .containsExactlyInAnyOrder(
            new EntryRow("MERCHANT_PAYABLE", merchant, "DEBIT", 300, "CAD"),
            new EntryRow("PROCESSOR_RECEIVABLE", null, "CREDIT", 300, "CAD"));
    assertThat(merchantPayableBalance(merchant)).isEqualTo(700);
  }

  @Test
  void unbalancedJournalWritesNothing() {
    UUID merchant = rows.merchant();
    UUID payment = rows.payment(merchant, "CAPTURED", 1000, 0);
    long journalsBefore = count("journal_transactions");
    long entriesBefore = count("ledger_entries");
    Journal unbalanced =
        new Journal(
            payment,
            JournalKind.CAPTURE,
            List.of(
                new Posting(UUID.randomUUID(), Direction.DEBIT, Money.of(1000, CAD)),
                new Posting(UUID.randomUUID(), Direction.CREDIT, Money.of(999, CAD))));

    assertThatThrownBy(() -> ledger.post(unbalanced))
        .isInstanceOf(UnbalancedJournalException.class);

    assertThat(count("journal_transactions")).isEqualTo(journalsBefore);
    assertThat(count("ledger_entries")).isEqualTo(entriesBefore);
  }

  @Test
  void rejectsEntryWhoseCurrencyDiffersFromItsAccount() {
    UUID merchant = rows.merchant();
    UUID payment = rows.payment(merchant, "CAPTURED", 1000, 0);
    UUID cadReceivable = repository.accountId(AccountType.PROCESSOR_RECEIVABLE, null, CAD);
    UUID cadPayable = repository.accountId(AccountType.MERCHANT_PAYABLE, merchant, CAD);
    Journal usdOnCadAccounts =
        new Journal(
            payment,
            JournalKind.CAPTURE,
            List.of(
                new Posting(cadReceivable, Direction.DEBIT, Money.of(1000, USD)),
                new Posting(cadPayable, Direction.CREDIT, Money.of(1000, USD))));

    assertThatThrownBy(() -> ledger.post(usdOnCadAccounts))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("ledger_entries_account_currency_fk");
  }

  @Test
  void rejectsSecondCaptureForSamePayment() {
    UUID merchant = rows.merchant();
    UUID payment = rows.payment(merchant, "CAPTURED", 1000, 0);
    ledger.recordCapture(payment, merchant, Money.of(1000, CAD));

    assertThatThrownBy(() -> ledger.recordCapture(payment, merchant, Money.of(1000, CAD)))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("journal_transactions_one_capture_per_payment");
  }

  @Test
  void reusesAccountsAcrossJournals() {
    UUID merchant = rows.merchant();
    UUID first = rows.payment(merchant, "CAPTURED", 1000, 0);
    UUID second = rows.payment(merchant, "CAPTURED", 1000, 0);

    ledger.recordCapture(first, merchant, Money.of(1000, CAD));
    ledger.recordCapture(second, merchant, Money.of(1000, CAD));

    long merchantAccounts =
        jdbc.sql("SELECT count(*) FROM ledger_accounts WHERE merchant_id = :merchantId")
            .param("merchantId", merchant)
            .query(Long.class)
            .single();
    long platformCadAccounts =
        jdbc.sql(
                """
                SELECT count(*) FROM ledger_accounts
                WHERE type = 'PROCESSOR_RECEIVABLE' AND currency = 'CAD'
                """)
            .query(Long.class)
            .single();
    assertThat(merchantAccounts).isEqualTo(1);
    assertThat(platformCadAccounts).isEqualTo(1);
    assertThat(merchantPayableBalance(merchant)).isEqualTo(2000);
  }
}
