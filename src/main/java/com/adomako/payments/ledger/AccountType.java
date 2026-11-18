package com.adomako.payments.ledger;

/** The chart of accounts. See docs/DECISIONS.md for the posting table. */
public enum AccountType {
  /** Platform asset, one per currency: captured money the processor owes us. */
  PROCESSOR_RECEIVABLE,
  /** Liability, one per merchant and currency: money we owe the merchant. */
  MERCHANT_PAYABLE
}
