package com.adomako.payments.ledger;

import static com.adomako.payments.ledger.Direction.CREDIT;
import static com.adomako.payments.ledger.Direction.DEBIT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.adomako.payments.common.Money;
import java.util.Currency;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class LedgerServiceTest {

  private static final Currency CAD = Currency.getInstance("CAD");
  private static final Currency USD = Currency.getInstance("USD");

  private final LedgerRepository repository = mock(LedgerRepository.class);
  private final LedgerService service = new LedgerService(repository);

  private static Posting debit(long minorUnits, Currency currency) {
    return new Posting(UUID.randomUUID(), DEBIT, Money.of(minorUnits, currency));
  }

  private static Posting credit(long minorUnits, Currency currency) {
    return new Posting(UUID.randomUUID(), CREDIT, Money.of(minorUnits, currency));
  }

  private static Journal journal(Posting... postings) {
    return new Journal(UUID.randomUUID(), JournalKind.CAPTURE, List.of(postings));
  }

  @Test
  void rejectsDebitsNotEqualToCredits() {
    Journal unbalanced = journal(debit(100, CAD), credit(99, CAD));

    assertThatThrownBy(() -> service.post(unbalanced))
        .isInstanceOf(UnbalancedJournalException.class)
        .hasMessageContaining("CAD");
    verifyNoInteractions(repository);
  }

  @Test
  void rejectsJournalBalancedOverallButNotPerCurrency() {
    Journal unbalanced =
        journal(debit(100, CAD), credit(50, CAD), debit(50, USD), credit(100, USD));

    assertThatThrownBy(() -> service.post(unbalanced))
        .isInstanceOf(UnbalancedJournalException.class);
    verifyNoInteractions(repository);
  }

  @Test
  void rejectsEmptyJournal() {
    assertThatThrownBy(() -> service.post(journal()))
        .isInstanceOf(UnbalancedJournalException.class);
    verifyNoInteractions(repository);
  }

  @Test
  void rejectsSinglePosting() {
    assertThatThrownBy(() -> service.post(journal(debit(100, CAD))))
        .isInstanceOf(UnbalancedJournalException.class);
    verifyNoInteractions(repository);
  }

  @Test
  void rejectsOneSidedJournal() {
    assertThatThrownBy(() -> service.post(journal(debit(100, CAD), debit(100, CAD))))
        .isInstanceOf(UnbalancedJournalException.class);
    verifyNoInteractions(repository);
  }

  @Test
  void unbalancedJournalExceptionIsAnIllegalArgumentException() {
    assertThat(IllegalArgumentException.class).isAssignableFrom(UnbalancedJournalException.class);
  }

  @Test
  void postsBalancedJournal() {
    Journal balanced = journal(debit(1000, CAD), credit(1000, CAD));

    service.post(balanced);

    verify(repository).insert(balanced);
  }

  @Test
  void postsJournalBalancedInEachOfTwoCurrencies() {
    Journal balanced =
        journal(debit(100, CAD), credit(60, CAD), credit(40, CAD), debit(50, USD), credit(50, USD));

    service.post(balanced);

    verify(repository).insert(balanced);
  }

  @Test
  void postingRejectsZeroAmount() {
    assertThatThrownBy(() -> new Posting(UUID.randomUUID(), DEBIT, Money.of(0, CAD)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void postingRejectsNullFields() {
    Money amount = Money.of(100, CAD);

    assertThatThrownBy(() -> new Posting(null, DEBIT, amount))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new Posting(UUID.randomUUID(), null, amount))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new Posting(UUID.randomUUID(), DEBIT, null))
        .isInstanceOf(NullPointerException.class);
  }
}
