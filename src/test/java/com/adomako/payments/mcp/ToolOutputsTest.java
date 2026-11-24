package com.adomako.payments.mcp;

import static com.adomako.payments.ledger.AccountType.MERCHANT_PAYABLE;
import static com.adomako.payments.ledger.AccountType.PROCESSOR_RECEIVABLE;
import static com.adomako.payments.ledger.Direction.CREDIT;
import static com.adomako.payments.ledger.Direction.DEBIT;
import static com.adomako.payments.ledger.JournalKind.CAPTURE;
import static com.adomako.payments.ledger.JournalKind.REFUND;
import static org.assertj.core.api.Assertions.assertThat;

import com.adomako.payments.common.Money;
import com.adomako.payments.ledger.AccountType;
import com.adomako.payments.ledger.Direction;
import com.adomako.payments.ledger.JournalKind;
import com.adomako.payments.ledger.LedgerEntryView;
import com.adomako.payments.ledger.PaymentLedger;
import com.adomako.payments.mcp.ToolOutputs.LedgerBalance;
import com.adomako.payments.mcp.ToolOutputs.MoneyView;
import com.adomako.payments.mcp.ToolOutputs.PaymentView;
import com.adomako.payments.mcp.ToolOutputs.TotalsView;
import com.adomako.payments.payment.PaymentResponse;
import com.adomako.payments.payment.PaymentStatus;
import com.adomako.payments.payment.RefundResponse;
import java.time.Instant;
import java.util.Currency;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ToolOutputsTest {

  private static final Currency CAD = Currency.getInstance("CAD");
  private static final Currency JPY = Currency.getInstance("JPY");
  private static final UUID MERCHANT = UUID.randomUUID();
  private static final Instant NOW = Instant.parse("2026-11-23T12:00:00Z");

  static PaymentResponse payment(
      PaymentStatus status, long amount, long captured, long refunded, RefundResponse... refunds) {
    UUID id = refunds.length > 0 ? refunds[0].paymentId() : UUID.randomUUID();
    return new PaymentResponse(
        id,
        MERCHANT,
        status,
        amount,
        "CAD",
        captured,
        refunded,
        "proc_1",
        List.of(refunds),
        NOW,
        NOW);
  }

  private static LedgerEntryView entry(
      JournalKind kind, AccountType type, Direction direction, long amount) {
    UUID merchant = type == MERCHANT_PAYABLE ? MERCHANT : null;
    return new LedgerEntryView(
        UUID.randomUUID(), kind, type, merchant, direction, Money.of(amount, CAD), NOW);
  }

  @Test
  void moneyViewHasMinorUnitsCurrencyAndDisplay() {
    assertThat(MoneyView.of(Money.of(1050, CAD)))
        .isEqualTo(new MoneyView(1050, "CAD", "10.50 CAD"));
    assertThat(MoneyView.of(Money.of(1050, JPY))).isEqualTo(new MoneyView(1050, "JPY", "1050 JPY"));
  }

  @Test
  void paymentViewStatesEveryAmountWithItsCurrency() {
    UUID paymentId = UUID.randomUUID();
    RefundResponse refund = new RefundResponse(UUID.randomUUID(), paymentId, 300, "CAD", NOW);

    PaymentView view =
        PaymentView.from(payment(PaymentStatus.PARTIALLY_REFUNDED, 1050, 1050, 300, refund));

    assertThat(view.id()).isEqualTo(paymentId);
    assertThat(view.merchantId()).isEqualTo(MERCHANT);
    assertThat(view.status()).isEqualTo(PaymentStatus.PARTIALLY_REFUNDED);
    assertThat(view.amount()).isEqualTo(new MoneyView(1050, "CAD", "10.50 CAD"));
    assertThat(view.captured()).isEqualTo(new MoneyView(1050, "CAD", "10.50 CAD"));
    assertThat(view.refunded()).isEqualTo(new MoneyView(300, "CAD", "3.00 CAD"));
    assertThat(view.refundable()).isEqualTo(new MoneyView(750, "CAD", "7.50 CAD"));
    assertThat(view.refunds()).hasSize(1);
    assertThat(view.refunds().getFirst().id()).isEqualTo(refund.id());
    assertThat(view.refunds().getFirst().amount()).isEqualTo(new MoneyView(300, "CAD", "3.00 CAD"));
    assertThat(view.createdAt()).isEqualTo(NOW);
  }

  @Test
  void uncapturedPaymentHasNothingRefundable() {
    PaymentView view = PaymentView.from(payment(PaymentStatus.AUTHORIZED, 1050, 0, 0));

    assertThat(view.refundable()).isEqualTo(new MoneyView(0, "CAD", "0.00 CAD"));
  }

  @Test
  void ledgerBalanceForACaptureAndPartialRefund() {
    PaymentResponse payment = payment(PaymentStatus.PARTIALLY_REFUNDED, 1050, 1050, 300);
    PaymentLedger ledger =
        new PaymentLedger(
            payment.id(),
            List.of(
                entry(CAPTURE, PROCESSOR_RECEIVABLE, DEBIT, 1050),
                entry(CAPTURE, MERCHANT_PAYABLE, CREDIT, 1050),
                entry(REFUND, MERCHANT_PAYABLE, DEBIT, 300),
                entry(REFUND, PROCESSOR_RECEIVABLE, CREDIT, 300)));

    LedgerBalance balance = LedgerBalance.from(payment, ledger);

    assertThat(balance.paymentId()).isEqualTo(payment.id());
    assertThat(balance.paymentStatus()).isEqualTo(PaymentStatus.PARTIALLY_REFUNDED);
    assertThat(balance.balanced()).isTrue();
    assertThat(balance.matchesPayment()).isTrue();
    assertThat(balance.merchantPayableBalance()).isEqualTo(new MoneyView(750, "CAD", "7.50 CAD"));
    assertThat(balance.expectedMerchantPayableBalance())
        .isEqualTo(new MoneyView(750, "CAD", "7.50 CAD"));
    assertThat(balance.totals())
        .containsExactly(
            new TotalsView(
                "CAD",
                new MoneyView(1350, "CAD", "13.50 CAD"),
                new MoneyView(1350, "CAD", "13.50 CAD"),
                true));
    assertThat(balance.entries()).hasSize(4);
    assertThat(balance.entries().get(2).journalKind()).isEqualTo(REFUND);
    assertThat(balance.entries().get(2).account()).isEqualTo(MERCHANT_PAYABLE);
    assertThat(balance.entries().get(2).merchantId()).isEqualTo(MERCHANT);
    assertThat(balance.entries().get(2).direction()).isEqualTo(DEBIT);
    assertThat(balance.entries().get(2).amount()).isEqualTo(new MoneyView(300, "CAD", "3.00 CAD"));
  }

  @Test
  void ledgerThatDisagreesWithThePaymentDoesNotMatch() {
    // Payment says 300 refunded, but the ledger has no refund journal.
    PaymentResponse payment = payment(PaymentStatus.PARTIALLY_REFUNDED, 1050, 1050, 300);
    PaymentLedger ledger =
        new PaymentLedger(
            payment.id(),
            List.of(
                entry(CAPTURE, PROCESSOR_RECEIVABLE, DEBIT, 1050),
                entry(CAPTURE, MERCHANT_PAYABLE, CREDIT, 1050)));

    LedgerBalance balance = LedgerBalance.from(payment, ledger);

    assertThat(balance.balanced()).isTrue();
    assertThat(balance.matchesPayment()).isFalse();
    assertThat(balance.merchantPayableBalance()).isEqualTo(new MoneyView(1050, "CAD", "10.50 CAD"));
    assertThat(balance.expectedMerchantPayableBalance())
        .isEqualTo(new MoneyView(750, "CAD", "7.50 CAD"));
  }

  @Test
  void uncapturedPaymentHasAnEmptyBalancedLedger() {
    PaymentResponse payment = payment(PaymentStatus.AUTHORIZED, 1050, 0, 0);

    LedgerBalance balance = LedgerBalance.from(payment, new PaymentLedger(payment.id(), List.of()));

    assertThat(balance.balanced()).isTrue();
    assertThat(balance.matchesPayment()).isTrue();
    assertThat(balance.totals()).isEmpty();
    assertThat(balance.entries()).isEmpty();
    assertThat(balance.merchantPayableBalance()).isEqualTo(new MoneyView(0, "CAD", "0.00 CAD"));
  }
}
