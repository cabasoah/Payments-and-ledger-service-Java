package com.adomako.payments.ledger;

import java.util.Currency;
import java.util.UUID;

/** A merchant whose ledger balance differs from captured minus refunded in the payments table. */
public record MerchantMismatch(
    UUID merchantId, Currency currency, long ledgerBalance, long expectedBalance) {}
