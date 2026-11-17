package com.adomako.payments.common;

import java.util.Currency;

public class CurrencyMismatchException extends IllegalArgumentException {

  public CurrencyMismatchException(Currency expected, Currency actual) {
    super("Currency mismatch: expected " + expected + " but got " + actual);
  }
}
