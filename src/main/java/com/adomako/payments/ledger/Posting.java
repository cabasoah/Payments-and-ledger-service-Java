package com.adomako.payments.ledger;

import com.adomako.payments.common.Money;
import java.util.Objects;
import java.util.UUID;

/** One side of a journal: an amount debited or credited to an account. */
public record Posting(UUID accountId, Direction direction, Money amount) {

  public Posting {
    Objects.requireNonNull(accountId, "accountId");
    Objects.requireNonNull(direction, "direction");
    Objects.requireNonNull(amount, "amount");
    if (amount.minorUnits() <= 0) {
      throw new IllegalArgumentException("Posting amount must be positive: " + amount);
    }
  }
}
