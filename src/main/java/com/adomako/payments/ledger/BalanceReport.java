package com.adomako.payments.ledger;

import java.util.List;

public record BalanceReport(
    List<CurrencyImbalance> ledgerImbalances, List<MerchantMismatch> merchantMismatches) {

  public BalanceReport {
    ledgerImbalances = List.copyOf(ledgerImbalances);
    merchantMismatches = List.copyOf(merchantMismatches);
  }

  public boolean isBalanced() {
    return ledgerImbalances.isEmpty() && merchantMismatches.isEmpty();
  }
}
