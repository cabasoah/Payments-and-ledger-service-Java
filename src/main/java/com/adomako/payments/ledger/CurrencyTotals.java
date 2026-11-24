package com.adomako.payments.ledger;

import com.adomako.payments.common.Money;
import java.util.Currency;

/** Total debits and credits in one currency. */
public record CurrencyTotals(Money debits, Money credits) {

  static CurrencyTotals zero(Currency currency) {
    Money zero = Money.of(0, currency);
    return new CurrencyTotals(zero, zero);
  }

  CurrencyTotals plus(Direction direction, Money amount) {
    return direction == Direction.DEBIT
        ? new CurrencyTotals(debits.add(amount), credits)
        : new CurrencyTotals(debits, credits.add(amount));
  }

  public boolean balanced() {
    return debits.equals(credits);
  }
}
