package com.adomako.payments.ledger;

import java.util.Currency;

/** Total debits and credits for a currency that do not match. */
public record CurrencyImbalance(Currency currency, long debits, long credits) {}
