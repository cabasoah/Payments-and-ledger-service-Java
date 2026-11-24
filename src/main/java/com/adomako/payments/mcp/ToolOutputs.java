package com.adomako.payments.mcp;

import com.adomako.payments.common.Money;
import com.adomako.payments.ledger.AccountType;
import com.adomako.payments.ledger.CurrencyTotals;
import com.adomako.payments.ledger.Direction;
import com.adomako.payments.ledger.JournalKind;
import com.adomako.payments.ledger.LedgerEntryView;
import com.adomako.payments.ledger.PaymentLedger;
import com.adomako.payments.payment.PaymentResponse;
import com.adomako.payments.payment.PaymentStatus;
import com.adomako.payments.payment.RefundResponse;
import java.time.Instant;
import java.util.Currency;
import java.util.List;
import java.util.UUID;

/**
 * What the tools return to the model. Every amount is a {@link MoneyView}: integer minor units, the
 * currency code, and a display string, so the model never has to guess units.
 */
public final class ToolOutputs {

  private ToolOutputs() {}

  private static Money money(long minorUnits, String currency) {
    return Money.of(minorUnits, Currency.getInstance(currency));
  }

  /** {@code {"amount": 1050, "currency": "CAD", "display": "10.50 CAD"}}. */
  public record MoneyView(long amount, String currency, String display) {

    static MoneyView of(Money money) {
      return new MoneyView(
          money.minorUnits(), money.currency().getCurrencyCode(), money.toDisplayString());
    }
  }

  public record RefundView(UUID id, MoneyView amount, Instant createdAt) {

    static RefundView from(RefundResponse refund) {
      return new RefundView(
          refund.id(), MoneyView.of(money(refund.amount(), refund.currency())), refund.createdAt());
    }
  }

  /** A payment. {@code refundable} is captured minus refunded: the most a refund can be. */
  public record PaymentView(
      UUID id,
      UUID merchantId,
      PaymentStatus status,
      MoneyView amount,
      MoneyView captured,
      MoneyView refunded,
      MoneyView refundable,
      List<RefundView> refunds,
      Instant createdAt,
      Instant updatedAt) {

    static PaymentView from(PaymentResponse payment) {
      Money captured = money(payment.capturedAmount(), payment.currency());
      Money refunded = money(payment.refundedAmount(), payment.currency());
      return new PaymentView(
          payment.id(),
          payment.merchantId(),
          payment.status(),
          MoneyView.of(money(payment.amount(), payment.currency())),
          MoneyView.of(captured),
          MoneyView.of(refunded),
          MoneyView.of(captured.subtract(refunded)),
          payment.refunds().stream().map(RefundView::from).toList(),
          payment.createdAt(),
          payment.updatedAt());
    }
  }

  /** Result of create_payment and capture_payment. */
  public record PaymentResult(PaymentView payment, boolean replayed) {}

  /** Result of refund_payment: the refund, and the payment as it is after the refund. */
  public record RefundResult(RefundView refund, PaymentView payment, boolean replayed) {}

  public record PaymentList(UUID merchantId, int count, List<PaymentView> payments) {}

  public record EntryView(
      UUID journalId,
      JournalKind journalKind,
      AccountType account,
      UUID merchantId,
      Direction direction,
      MoneyView amount,
      Instant createdAt) {

    static EntryView from(LedgerEntryView entry) {
      return new EntryView(
          entry.journalId(),
          entry.journalKind(),
          entry.accountType(),
          entry.merchantId(),
          entry.direction(),
          MoneyView.of(entry.amount()),
          entry.createdAt());
    }
  }

  public record TotalsView(String currency, MoneyView debits, MoneyView credits, boolean balanced) {

    static TotalsView from(CurrencyTotals totals) {
      return new TotalsView(
          totals.debits().currency().getCurrencyCode(),
          MoneyView.of(totals.debits()),
          MoneyView.of(totals.credits()),
          totals.balanced());
    }
  }

  /**
   * The ledger check for one payment. {@code balanced}: debits equal credits in every currency.
   * {@code matchesPayment}: what we owe the merchant for this payment ({@code
   * merchantPayableBalance}) equals captured minus refunded ({@code
   * expectedMerchantPayableBalance}).
   */
  public record LedgerBalance(
      UUID paymentId,
      PaymentStatus paymentStatus,
      boolean balanced,
      boolean matchesPayment,
      MoneyView merchantPayableBalance,
      MoneyView expectedMerchantPayableBalance,
      List<TotalsView> totals,
      List<EntryView> entries) {

    static LedgerBalance from(PaymentResponse payment, PaymentLedger ledger) {
      Currency currency = Currency.getInstance(payment.currency());
      Money expected =
          money(payment.capturedAmount(), payment.currency())
              .subtract(money(payment.refundedAmount(), payment.currency()));
      Money actual = ledger.merchantPayableBalance(currency);
      return new LedgerBalance(
          payment.id(),
          payment.status(),
          ledger.balanced(),
          actual.equals(expected),
          MoneyView.of(actual),
          MoneyView.of(expected),
          ledger.totals().stream().map(TotalsView::from).toList(),
          ledger.entries().stream().map(EntryView::from).toList());
    }
  }
}
