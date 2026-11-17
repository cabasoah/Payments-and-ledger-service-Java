package com.adomako.payments.ledger;

import static com.adomako.payments.ledger.AccountType.MERCHANT_PAYABLE;
import static com.adomako.payments.ledger.AccountType.PROCESSOR_RECEIVABLE;
import static com.adomako.payments.ledger.Direction.CREDIT;
import static com.adomako.payments.ledger.Direction.DEBIT;

import com.adomako.payments.common.Money;
import java.util.Currency;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LedgerService {

  private final LedgerRepository repository;

  public LedgerService(LedgerRepository repository) {
    this.repository = repository;
  }

  /** Records a journal. Rejects it unless debits equal credits in every currency. */
  @Transactional
  public void post(Journal journal) {
    requireBalanced(journal);
    repository.insert(journal);
  }

  /** Capture: debit PROCESSOR_RECEIVABLE, credit the merchant's MERCHANT_PAYABLE. */
  @Transactional
  public void recordCapture(UUID paymentId, UUID merchantId, Money amount) {
    UUID receivable = repository.accountId(PROCESSOR_RECEIVABLE, null, amount.currency());
    UUID payable = repository.accountId(MERCHANT_PAYABLE, merchantId, amount.currency());
    post(
        new Journal(
            paymentId,
            JournalKind.CAPTURE,
            List.of(new Posting(receivable, DEBIT, amount), new Posting(payable, CREDIT, amount))));
  }

  /** Refund: debit the merchant's MERCHANT_PAYABLE, credit PROCESSOR_RECEIVABLE. */
  @Transactional
  public void recordRefund(UUID paymentId, UUID merchantId, Money amount) {
    UUID receivable = repository.accountId(PROCESSOR_RECEIVABLE, null, amount.currency());
    UUID payable = repository.accountId(MERCHANT_PAYABLE, merchantId, amount.currency());
    post(
        new Journal(
            paymentId,
            JournalKind.REFUND,
            List.of(new Posting(payable, DEBIT, amount), new Posting(receivable, CREDIT, amount))));
  }

  private static void requireBalanced(Journal journal) {
    if (journal.postings().size() < 2) {
      throw new UnbalancedJournalException(
          "Journal needs at least two postings, got " + journal.postings().size());
    }

    Map<Currency, Money> debits = new HashMap<>();
    Map<Currency, Money> credits = new HashMap<>();
    for (Posting posting : journal.postings()) {
      Map<Currency, Money> side = posting.direction() == DEBIT ? debits : credits;
      side.merge(posting.amount().currency(), posting.amount(), Money::add);
    }

    Set<Currency> currencies = new HashSet<>(debits.keySet());
    currencies.addAll(credits.keySet());
    for (Currency currency : currencies) {
      Money zero = Money.of(0, currency);
      Money debit = debits.getOrDefault(currency, zero);
      Money credit = credits.getOrDefault(currency, zero);
      if (!debit.equals(credit)) {
        throw new UnbalancedJournalException(
            "Journal is unbalanced in "
                + currency.getCurrencyCode()
                + ": debits "
                + debit.toDisplayString()
                + ", credits "
                + credit.toDisplayString());
      }
    }
  }
}
