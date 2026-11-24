package com.adomako.payments.ledger;

import com.adomako.payments.common.Money;
import java.util.Currency;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/** Every ledger entry recorded for one payment, with debit and credit totals per currency. */
public record PaymentLedger(UUID paymentId, List<LedgerEntryView> entries) {

  public PaymentLedger {
    entries = List.copyOf(entries);
  }

  /** Debit and credit totals for each currency that has entries, ordered by currency code. */
  public List<CurrencyTotals> totals() {
    Map<String, CurrencyTotals> byCode = new TreeMap<>();
    for (LedgerEntryView entry : entries) {
      Currency currency = entry.amount().currency();
      String code = currency.getCurrencyCode();
      CurrencyTotals soFar = byCode.getOrDefault(code, CurrencyTotals.zero(currency));
      byCode.put(code, soFar.plus(entry.direction(), entry.amount()));
    }
    return List.copyOf(byCode.values());
  }

  /** True when debits equal credits in every currency. A payment with no entries is balanced. */
  public boolean balanced() {
    return totals().stream().allMatch(CurrencyTotals::balanced);
  }

  /**
   * Credits minus debits on the merchant's payable account for this payment: what we owe the
   * merchant for it. Should equal captured minus refunded.
   */
  public Money merchantPayableBalance(Currency currency) {
    Money balance = Money.of(0, currency);
    for (LedgerEntryView entry : entries) {
      if (entry.accountType() != AccountType.MERCHANT_PAYABLE
          || !entry.amount().currency().equals(currency)) {
        continue;
      }
      balance =
          entry.direction() == Direction.CREDIT
              ? balance.add(entry.amount())
              : balance.subtractAllowingNegative(entry.amount());
    }
    return balance;
  }
}
