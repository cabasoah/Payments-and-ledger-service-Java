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
import java.util.List;
import java.util.UUID;

/**
 * What the tools return to the model. Every amount is a {@link MoneyView}: integer minor units, the
 * currency code, and a display string, so the model never has to guess units.
 */
public final class ToolOutputs {

  private ToolOutputs() {}

  /** {@code {"amount": 1050, "currency": "CAD", "display": "10.50 CAD"}}. */
  public record MoneyView(long amount, String currency, String display) {

    static MoneyView of(Money money) {
      throw new UnsupportedOperationException("not implemented yet");
    }
  }

  public record RefundView(UUID id, MoneyView amount, Instant createdAt) {

    static RefundView from(RefundResponse refund) {
      throw new UnsupportedOperationException("not implemented yet");
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
      throw new UnsupportedOperationException("not implemented yet");
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
      throw new UnsupportedOperationException("not implemented yet");
    }
  }

  public record TotalsView(String currency, MoneyView debits, MoneyView credits, boolean balanced) {

    static TotalsView from(CurrencyTotals totals) {
      throw new UnsupportedOperationException("not implemented yet");
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
      throw new UnsupportedOperationException("not implemented yet");
    }
  }
}
