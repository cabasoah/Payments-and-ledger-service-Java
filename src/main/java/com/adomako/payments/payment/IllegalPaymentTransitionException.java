package com.adomako.payments.payment;

public class IllegalPaymentTransitionException extends IllegalStateException {

  public IllegalPaymentTransitionException(PaymentStatus from, PaymentStatus to) {
    super("Payment cannot move from " + from + " to " + to);
  }
}
