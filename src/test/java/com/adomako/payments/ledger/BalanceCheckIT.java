package com.adomako.payments.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import com.adomako.payments.AbstractIT;
import com.adomako.payments.common.Money;
import java.util.Currency;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * BalanceCheck looks at every row in the database. Each test runs in a rolled-back transaction (see
 * AbstractIT), so the only data it sees is what the test inserts.
 */
class BalanceCheckIT extends AbstractIT {

  private static final Currency CAD = Currency.getInstance("CAD");

  @Autowired BalanceCheck balanceCheck;
  @Autowired LedgerService ledger;
  @Autowired JdbcClient jdbc;

  private TestRows rows;

  @BeforeEach
  void setUp() {
    rows = new TestRows(jdbc);
  }

  @Test
  void balancedAfterCaptureAndPartialRefund() {
    UUID merchant = rows.merchant();
    UUID payment = rows.payment(merchant, "PARTIALLY_REFUNDED", 1000, 300);
    ledger.recordCapture(payment, merchant, Money.of(1000, CAD));
    ledger.recordRefund(payment, merchant, Money.of(300, CAD));

    BalanceReport report = balanceCheck.run();

    assertThat(report.ledgerImbalances()).isEmpty();
    assertThat(report.merchantMismatches()).isEmpty();
    assertThat(report.isBalanced()).isTrue();
  }

  @Test
  void detectsEntryCorruptedWithTriggerDisabled() {
    UUID merchant = rows.merchant();
    UUID payment = rows.payment(merchant, "CAPTURED", 1000, 0);
    ledger.recordCapture(payment, merchant, Money.of(1000, CAD));
    assertThat(balanceCheck.run().isBalanced()).isTrue();

    // Simulates someone with database access editing history. ALTER TABLE is transactional in
    // Postgres, so the test's rollback restores the trigger and the row.
    jdbc.sql("ALTER TABLE ledger_entries DISABLE TRIGGER ledger_entries_append_only").update();
    jdbc.sql(
            """
            UPDATE ledger_entries SET amount_minor = 999
            WHERE direction = 'CREDIT'
              AND journal_transaction_id IN
                  (SELECT id FROM journal_transactions WHERE payment_id = :paymentId)
            """)
        .param("paymentId", payment)
        .update();
    jdbc.sql("ALTER TABLE ledger_entries ENABLE TRIGGER ledger_entries_append_only").update();

    BalanceReport report = balanceCheck.run();

    assertThat(report.isBalanced()).isFalse();
    assertThat(report.ledgerImbalances()).containsExactly(new CurrencyImbalance(CAD, 1000, 999));
    assertThat(report.merchantMismatches())
        .containsExactly(new MerchantMismatch(merchant, CAD, 999, 1000));
  }

  @Test
  void detectsCapturedPaymentWithNoLedgerEntries() {
    UUID merchant = rows.merchant();
    rows.payment(merchant, "CAPTURED", 1000, 0);

    BalanceReport report = balanceCheck.run();

    assertThat(report.isBalanced()).isFalse();
    assertThat(report.ledgerImbalances()).isEmpty();
    assertThat(report.merchantMismatches())
        .containsExactly(new MerchantMismatch(merchant, CAD, 0, 1000));
  }

  @Test
  void detectsLedgerCaptureForPaymentThatWasNeverCaptured() {
    UUID merchant = rows.merchant();
    UUID payment = rows.payment(merchant, "AUTHORIZED", 0, 0);
    ledger.recordCapture(payment, merchant, Money.of(1000, CAD));

    BalanceReport report = balanceCheck.run();

    assertThat(report.isBalanced()).isFalse();
    assertThat(report.ledgerImbalances()).isEmpty();
    assertThat(report.merchantMismatches())
        .containsExactly(new MerchantMismatch(merchant, CAD, 1000, 0));
  }
}
