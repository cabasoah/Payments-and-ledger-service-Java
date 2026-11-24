package com.adomako.payments.ledger;

import com.adomako.payments.common.Money;

/** Total debits and credits in one currency. */
public record CurrencyTotals(Money debits, Money credits) {

  public boolean balanced() {
    throw new UnsupportedOperationException("not implemented yet");
  }
}
