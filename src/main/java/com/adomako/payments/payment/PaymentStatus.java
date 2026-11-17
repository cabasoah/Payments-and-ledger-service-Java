package com.adomako.payments.payment;

import java.util.Map;
import java.util.Objects;
import java.util.Set;

public enum PaymentStatus {
  PENDING,
  AUTHORIZED,
  CAPTURED,
  PARTIALLY_REFUNDED,
  REFUNDED,
  FAILED;

  /** The single source of truth for allowed status moves. */
  private static final Map<PaymentStatus, Set<PaymentStatus>> ALLOWED_TRANSITIONS =
      Map.of(
          PENDING, Set.of(AUTHORIZED, FAILED),
          AUTHORIZED, Set.of(CAPTURED, FAILED),
          CAPTURED, Set.of(PARTIALLY_REFUNDED, REFUNDED),
          PARTIALLY_REFUNDED, Set.of(PARTIALLY_REFUNDED, REFUNDED),
          REFUNDED, Set.of(),
          FAILED, Set.of());

  public boolean canTransitionTo(PaymentStatus next) {
    Objects.requireNonNull(next, "next");
    return ALLOWED_TRANSITIONS.get(this).contains(next);
  }

  public void requireTransitionTo(PaymentStatus next) {
    if (!canTransitionTo(next)) {
      throw new IllegalPaymentTransitionException(this, next);
    }
  }
}
