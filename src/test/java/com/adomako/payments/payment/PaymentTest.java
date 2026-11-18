package com.adomako.payments.payment;

import static com.adomako.payments.payment.PaymentStatus.AUTHORIZED;
import static com.adomako.payments.payment.PaymentStatus.CAPTURED;
import static com.adomako.payments.payment.PaymentStatus.FAILED;
import static com.adomako.payments.payment.PaymentStatus.PARTIALLY_REFUNDED;
import static com.adomako.payments.payment.PaymentStatus.PENDING;
import static com.adomako.payments.payment.PaymentStatus.REFUNDED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.adomako.payments.common.CurrencyMismatchException;
import com.adomako.payments.common.Money;
import java.util.Currency;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The Payment aggregate's rules, without Spring or a database. */
class PaymentTest {

  private static final Currency CAD = Currency.getInstance("CAD");
  private static final Currency USD = Currency.getInstance("USD");

  private static Money cad(long minorUnits) {
    return Money.of(minorUnits, CAD);
  }

  private static Payment newPayment() {
    return Payment.create(UUID.randomUUID(), cad(1000));
  }

  private static Payment capturedPayment() {
    Payment payment = newPayment();
    payment.authorize("proc_test");
    payment.capture();
    return payment;
  }

  @Test
  void startsPendingWithNothingCapturedOrRefunded() {
    Payment payment = newPayment();

    assertThat(payment.getStatus()).isEqualTo(PENDING);
    assertThat(payment.getAmount()).isEqualTo(cad(1000));
    assertThat(payment.getCapturedAmount()).isEqualTo(cad(0));
    assertThat(payment.getRefundedAmount()).isEqualTo(cad(0));
    assertThat(payment.getProcessorRef()).isNull();
  }

  @Test
  void authorizeRecordsProcessorRef() {
    Payment payment = newPayment();

    payment.authorize("proc_123");

    assertThat(payment.getStatus()).isEqualTo(AUTHORIZED);
    assertThat(payment.getProcessorRef()).isEqualTo("proc_123");
  }

  @Test
  void declineFailsThePayment() {
    Payment payment = newPayment();

    payment.decline();

    assertThat(payment.getStatus()).isEqualTo(FAILED);
    assertThat(payment.getCapturedAmount()).isEqualTo(cad(0));
  }

  @Test
  void captureTakesTheFullAmount() {
    Payment payment = newPayment();
    payment.authorize("proc_123");

    payment.capture();

    assertThat(payment.getStatus()).isEqualTo(CAPTURED);
    assertThat(payment.getCapturedAmount()).isEqualTo(cad(1000));
  }

  @Test
  void cannotCaptureWhilePending() {
    assertThatThrownBy(() -> newPayment().capture())
        .isInstanceOf(IllegalPaymentTransitionException.class);
  }

  @Test
  void cannotCaptureFailedPayment() {
    Payment payment = newPayment();
    payment.decline();

    assertThatThrownBy(payment::capture).isInstanceOf(IllegalPaymentTransitionException.class);
  }

  @Test
  void cannotCaptureTwice() {
    Payment payment = capturedPayment();

    assertThatThrownBy(payment::capture).isInstanceOf(IllegalPaymentTransitionException.class);
    assertThat(payment.getCapturedAmount()).isEqualTo(cad(1000));
  }

  @Test
  void partialRefund() {
    Payment payment = capturedPayment();

    payment.refund(cad(300));

    assertThat(payment.getStatus()).isEqualTo(PARTIALLY_REFUNDED);
    assertThat(payment.getRefundedAmount()).isEqualTo(cad(300));
  }

  @Test
  void secondPartialRefundStaysPartiallyRefunded() {
    Payment payment = capturedPayment();
    payment.refund(cad(300));

    payment.refund(cad(200));

    assertThat(payment.getStatus()).isEqualTo(PARTIALLY_REFUNDED);
    assertThat(payment.getRefundedAmount()).isEqualTo(cad(500));
  }

  @Test
  void refundingTheRemainderFullyRefunds() {
    Payment payment = capturedPayment();
    payment.refund(cad(300));

    payment.refund(cad(700));

    assertThat(payment.getStatus()).isEqualTo(REFUNDED);
    assertThat(payment.getRefundedAmount()).isEqualTo(cad(1000));
  }

  @Test
  void fullRefundInOneGo() {
    Payment payment = capturedPayment();

    payment.refund(cad(1000));

    assertThat(payment.getStatus()).isEqualTo(REFUNDED);
  }

  @Test
  void refundOverCapturedIsRejectedAndChangesNothing() {
    Payment payment = capturedPayment();

    assertThatThrownBy(() -> payment.refund(cad(1001)))
        .isInstanceOf(RefundExceedsCapturedException.class);
    assertThat(payment.getStatus()).isEqualTo(CAPTURED);
    assertThat(payment.getRefundedAmount()).isEqualTo(cad(0));
  }

  @Test
  void refundOverRemainingIsRejectedAndChangesNothing() {
    Payment payment = capturedPayment();
    payment.refund(cad(300));

    assertThatThrownBy(() -> payment.refund(cad(701)))
        .isInstanceOf(RefundExceedsCapturedException.class);
    assertThat(payment.getStatus()).isEqualTo(PARTIALLY_REFUNDED);
    assertThat(payment.getRefundedAmount()).isEqualTo(cad(300));
  }

  @Test
  void cannotRefundBeforeCapture() {
    Payment payment = newPayment();
    payment.authorize("proc_123");

    assertThatThrownBy(() -> payment.refund(cad(100)))
        .isInstanceOf(IllegalPaymentTransitionException.class);
    assertThat(payment.getRefundedAmount()).isEqualTo(cad(0));
  }

  @Test
  void cannotRefundAfterFullyRefunded() {
    Payment payment = capturedPayment();
    payment.refund(cad(1000));

    assertThatThrownBy(() -> payment.refund(cad(1)))
        .isInstanceOf(IllegalPaymentTransitionException.class);
  }

  @Test
  void refundMustBePositive() {
    Payment payment = capturedPayment();

    assertThatThrownBy(() -> payment.refund(cad(0))).isInstanceOf(IllegalArgumentException.class);
    assertThat(payment.getRefundedAmount()).isEqualTo(cad(0));
  }

  @Test
  void refundInAnotherCurrencyIsRejected() {
    Payment payment = capturedPayment();

    assertThatThrownBy(() -> payment.refund(Money.of(100, USD)))
        .isInstanceOf(CurrencyMismatchException.class);
    assertThat(payment.getRefundedAmount()).isEqualTo(cad(0));
  }
}
