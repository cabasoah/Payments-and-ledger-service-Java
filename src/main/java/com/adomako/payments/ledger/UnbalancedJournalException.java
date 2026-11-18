package com.adomako.payments.ledger;

public class UnbalancedJournalException extends IllegalArgumentException {

  public UnbalancedJournalException(String message) {
    super(message);
  }
}
