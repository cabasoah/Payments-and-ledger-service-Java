package com.adomako.payments.ledger;

import static com.adomako.payments.ledger.AccountType.MERCHANT_PAYABLE;
import static com.adomako.payments.ledger.AccountType.PROCESSOR_RECEIVABLE;
import static com.adomako.payments.ledger.Direction.CREDIT;
import static com.adomako.payments.ledger.Direction.DEBIT;
import static com.adomako.payments.ledger.JournalKind.CAPTURE;
import static com.adomako.payments.ledger.JournalKind.REFUND;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.adomako.payments.AbstractIT;
import com.adomako.payments.common.Money;
import java.util.Currency;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Reading back the entries for one payment, against real ledger rows. */
class PaymentLedgerIT extends AbstractIT {

  private static final Currency CAD = Currency.getInstance("CAD");

  @Autowired LedgerService ledger;
  @Autowired JdbcClient jdbc;

  private TestRows rows;
  private UUID merchant;

  @BeforeEach
  void setUp() {
    rows = new TestRows(jdbc);
    merchant = rows.merchant();
  }

  @Test
  void returnsCaptureAndRefundEntriesForThePayment() {
    UUID payment = rows.payment(merchant, "PARTIALLY_REFUNDED", 1000, 300);
    ledger.recordCapture(payment, merchant, Money.of(1000, CAD));
    ledger.recordRefund(payment, merchant, Money.of(300, CAD));

    PaymentLedger result = ledger.entriesForPayment(payment);

    assertThat(result.paymentId()).isEqualTo(payment);
    assertThat(result.entries())
        .extracting(
            LedgerEntryView::journalKind,
            LedgerEntryView::accountType,
            LedgerEntryView::merchantId,
            LedgerEntryView::direction,
            LedgerEntryView::amount)
        .containsExactlyInAnyOrder(
            tuple(CAPTURE, PROCESSOR_RECEIVABLE, null, DEBIT, Money.of(1000, CAD)),
            tuple(CAPTURE, MERCHANT_PAYABLE, merchant, CREDIT, Money.of(1000, CAD)),
            tuple(REFUND, MERCHANT_PAYABLE, merchant, DEBIT, Money.of(300, CAD)),
            tuple(REFUND, PROCESSOR_RECEIVABLE, null, CREDIT, Money.of(300, CAD)));
    assertThat(result.balanced()).isTrue();
    assertThat(result.merchantPayableBalance(CAD)).isEqualTo(Money.of(700, CAD));
  }

  @Test
  void entriesOfTheSameJournalShareItsId() {
    UUID payment = rows.payment(merchant, "CAPTURED", 1000, 0);
    ledger.recordCapture(payment, merchant, Money.of(1000, CAD));

    PaymentLedger result = ledger.entriesForPayment(payment);

    assertThat(result.entries()).hasSize(2);
    assertThat(result.entries()).extracting(LedgerEntryView::journalId).doesNotContainNull();
    assertThat(result.entries().get(0).journalId()).isEqualTo(result.entries().get(1).journalId());
    assertThat(result.entries()).extracting(LedgerEntryView::createdAt).doesNotContainNull();
  }

  @Test
  void excludesOtherPaymentsEntries() {
    UUID payment = rows.payment(merchant, "CAPTURED", 1000, 0);
    UUID other = rows.payment(merchant, "CAPTURED", 1000, 0);
    ledger.recordCapture(payment, merchant, Money.of(1000, CAD));
    ledger.recordCapture(other, merchant, Money.of(1000, CAD));

    PaymentLedger result = ledger.entriesForPayment(payment);

    assertThat(result.entries()).hasSize(2);
    assertThat(result.merchantPayableBalance(CAD)).isEqualTo(Money.of(1000, CAD));
  }

  @Test
  void authorizedPaymentHasNoEntries() {
    UUID payment = rows.payment(merchant, "AUTHORIZED", 0, 0);

    PaymentLedger result = ledger.entriesForPayment(payment);

    assertThat(result.entries()).isEmpty();
    assertThat(result.balanced()).isTrue();
  }
}
