package com.adomako.payments.ledger;

import com.adomako.payments.common.Money;
import java.util.Currency;
import java.util.List;
import java.util.UUID;

/** Every ledger entry recorded for one payment, with debit and credit totals per currency. */
public record PaymentLedger(UUID paymentId, List<LedgerEntryView> entries) {

  public PaymentLedger {
    entries = List.copyOf(entries);
  }

  /** Debit and credit totals for each currency that has entries, ordered by currency code. */
  public List<CurrencyTotals> totals() {
    throw new UnsupportedOperationException("not implemented yet");
  }

  /** True when debits equal credits in every currency. A payment with no entries is balanced. */
  public boolean balanced() {
    throw new UnsupportedOperationException("not implemented yet");
  }

  /**
   * Credits minus debits on the merchant's payable account for this payment: what we owe the
   * merchant for it. Should equal captured minus refunded.
   */
  public Money merchantPayableBalance(Currency currency) {
    throw new UnsupportedOperationException("not implemented yet");
  }
}
