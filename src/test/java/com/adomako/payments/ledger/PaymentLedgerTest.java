package com.adomako.payments.ledger;

import static com.adomako.payments.ledger.AccountType.MERCHANT_PAYABLE;
import static com.adomako.payments.ledger.AccountType.PROCESSOR_RECEIVABLE;
import static com.adomako.payments.ledger.Direction.CREDIT;
import static com.adomako.payments.ledger.Direction.DEBIT;
import static com.adomako.payments.ledger.JournalKind.CAPTURE;
import static com.adomako.payments.ledger.JournalKind.REFUND;
import static org.assertj.core.api.Assertions.assertThat;

import com.adomako.payments.common.Money;
import java.time.Instant;
import java.util.Currency;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PaymentLedgerTest {

  private static final Currency CAD = Currency.getInstance("CAD");
  private static final Currency USD = Currency.getInstance("USD");
  private static final UUID PAYMENT = UUID.randomUUID();
  private static final UUID MERCHANT = UUID.randomUUID();
  private static final UUID CAPTURE_JOURNAL = UUID.randomUUID();
  private static final UUID REFUND_JOURNAL = UUID.randomUUID();

  private static LedgerEntryView entry(
      UUID journal,
      JournalKind kind,
      AccountType type,
      Direction direction,
      long amount,
      Currency currency) {
    UUID merchant = type == MERCHANT_PAYABLE ? MERCHANT : null;
    return new LedgerEntryView(
        journal, kind, type, merchant, direction, Money.of(amount, currency), Instant.now());
  }

  /** Capture 1050 CAD, then refund 300 CAD, exactly as LedgerService posts them. */
  private static List<LedgerEntryView> captureThenRefund() {
    return List.of(
        entry(CAPTURE_JOURNAL, CAPTURE, PROCESSOR_RECEIVABLE, DEBIT, 1050, CAD),
        entry(CAPTURE_JOURNAL, CAPTURE, MERCHANT_PAYABLE, CREDIT, 1050, CAD),
        entry(REFUND_JOURNAL, REFUND, MERCHANT_PAYABLE, DEBIT, 300, CAD),
        entry(REFUND_JOURNAL, REFUND, PROCESSOR_RECEIVABLE, CREDIT, 300, CAD));
  }

  @Test
  void noEntriesIsBalancedWithNoTotals() {
    PaymentLedger ledger = new PaymentLedger(PAYMENT, List.of());

    assertThat(ledger.entries()).isEmpty();
    assertThat(ledger.totals()).isEmpty();
    assertThat(ledger.balanced()).isTrue();
    assertThat(ledger.merchantPayableBalance(CAD)).isEqualTo(Money.of(0, CAD));
  }

  @Test
  void captureAndRefundAreBalanced() {
    PaymentLedger ledger = new PaymentLedger(PAYMENT, captureThenRefund());

    assertThat(ledger.totals())
        .containsExactly(new CurrencyTotals(Money.of(1350, CAD), Money.of(1350, CAD)));
    assertThat(ledger.totals().getFirst().balanced()).isTrue();
    assertThat(ledger.balanced()).isTrue();
  }

  @Test
  void merchantPayableBalanceIsCapturedMinusRefunded() {
    PaymentLedger ledger = new PaymentLedger(PAYMENT, captureThenRefund());

    assertThat(ledger.merchantPayableBalance(CAD)).isEqualTo(Money.of(750, CAD));
  }

  @Test
  void merchantPayableBalanceIgnoresOtherCurrencies() {
    PaymentLedger ledger = new PaymentLedger(PAYMENT, captureThenRefund());

    assertThat(ledger.merchantPayableBalance(USD)).isEqualTo(Money.of(0, USD));
  }

  @Test
  void detectsDebitsNotEqualToCredits() {
    // Can't be produced through LedgerService; this is the case the check exists to catch.
    PaymentLedger ledger =
        new PaymentLedger(
            PAYMENT,
            List.of(
                entry(CAPTURE_JOURNAL, CAPTURE, PROCESSOR_RECEIVABLE, DEBIT, 1050, CAD),
                entry(CAPTURE_JOURNAL, CAPTURE, MERCHANT_PAYABLE, CREDIT, 1000, CAD)));

    assertThat(ledger.totals())
        .containsExactly(new CurrencyTotals(Money.of(1050, CAD), Money.of(1000, CAD)));
    assertThat(ledger.totals().getFirst().balanced()).isFalse();
    assertThat(ledger.balanced()).isFalse();
  }

  @Test
  void totalsAreKeptPerCurrencyAndOrderedByCode() {
    PaymentLedger ledger =
        new PaymentLedger(
            PAYMENT,
            List.of(
                entry(CAPTURE_JOURNAL, CAPTURE, PROCESSOR_RECEIVABLE, DEBIT, 500, USD),
                entry(CAPTURE_JOURNAL, CAPTURE, MERCHANT_PAYABLE, CREDIT, 500, USD),
                entry(CAPTURE_JOURNAL, CAPTURE, PROCESSOR_RECEIVABLE, DEBIT, 700, CAD),
                entry(CAPTURE_JOURNAL, CAPTURE, MERCHANT_PAYABLE, CREDIT, 700, CAD)));

    assertThat(ledger.totals())
        .containsExactly(
            new CurrencyTotals(Money.of(700, CAD), Money.of(700, CAD)),
            new CurrencyTotals(Money.of(500, USD), Money.of(500, USD)));
    assertThat(ledger.balanced()).isTrue();
  }

  @Test
  void oneUnbalancedCurrencyMakesTheWholeLedgerUnbalanced() {
    PaymentLedger ledger =
        new PaymentLedger(
            PAYMENT,
            List.of(
                entry(CAPTURE_JOURNAL, CAPTURE, PROCESSOR_RECEIVABLE, DEBIT, 700, CAD),
                entry(CAPTURE_JOURNAL, CAPTURE, MERCHANT_PAYABLE, CREDIT, 700, CAD),
                entry(CAPTURE_JOURNAL, CAPTURE, PROCESSOR_RECEIVABLE, DEBIT, 500, USD)));

    assertThat(ledger.balanced()).isFalse();
  }

  @Test
  void currencyTotalsReportBalancedOnlyWhenEqual() {
    assertThat(new CurrencyTotals(Money.of(5, CAD), Money.of(5, CAD)).balanced()).isTrue();
    assertThat(new CurrencyTotals(Money.of(5, CAD), Money.of(4, CAD)).balanced()).isFalse();
  }
}
