package com.adomako.payments.payment;

import com.adomako.payments.common.Money;

public class RefundExceedsCapturedException extends RuntimeException {

  public RefundExceedsCapturedException(Money requested, Money remaining) {
    super(
        "Refund of "
            + requested.toDisplayString()
            + " exceeds the remaining refundable "
            + remaining.toDisplayString());
  }
}
