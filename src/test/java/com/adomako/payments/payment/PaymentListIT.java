package com.adomako.payments.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.adomako.payments.AbstractIT;
import com.adomako.payments.common.error.ApiException;
import com.adomako.payments.common.error.ErrorCode;
import com.adomako.payments.merchant.CreateMerchantRequest;
import com.adomako.payments.merchant.MerchantService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;

/** Listing a merchant's recent payments through PaymentService, against the real database. */
class PaymentListIT extends AbstractIT {

  @Autowired PaymentService payments;
  @Autowired MerchantService merchants;

  private UUID merchant;

  @BeforeEach
  void setUp() {
    merchant = newMerchant();
  }

  private UUID newMerchant() {
    return merchants.create(new CreateMerchantRequest("List Test Merchant")).id();
  }

  private PaymentResponse pay(UUID merchantId, long amount) {
    return payments.create(new CreatePaymentRequest(merchantId, amount, "CAD", "tok_visa_ok"));
  }

  @Test
  void newestFirst() {
    PaymentResponse first = pay(merchant, 100);
    PaymentResponse second = pay(merchant, 200);
    PaymentResponse third = pay(merchant, 300);

    List<PaymentResponse> result = payments.listRecent(merchant, 10);

    assertThat(result)
        .extracting(PaymentResponse::id)
        .containsExactly(third.id(), second.id(), first.id());
  }

  @Test
  void limitKeepsTheNewest() {
    pay(merchant, 100);
    PaymentResponse second = pay(merchant, 200);
    PaymentResponse third = pay(merchant, 300);

    List<PaymentResponse> result = payments.listRecent(merchant, 2);

    assertThat(result).extracting(PaymentResponse::id).containsExactly(third.id(), second.id());
  }

  @Test
  void onlyThatMerchantsPayments() {
    PaymentResponse mine = pay(merchant, 100);
    pay(newMerchant(), 200);

    List<PaymentResponse> result = payments.listRecent(merchant, 10);

    assertThat(result).extracting(PaymentResponse::id).containsExactly(mine.id());
  }

  @Test
  void includesAmountsAndRefunds() {
    PaymentResponse refunded = pay(merchant, 1000);
    payments.capture(refunded.id());
    RefundResponse refund = payments.refund(refunded.id(), new RefundRequest(300L));
    PaymentResponse untouched = pay(merchant, 500);

    List<PaymentResponse> result = payments.listRecent(merchant, 10);

    assertThat(result).hasSize(2);
    PaymentResponse newest = result.get(0);
    assertThat(newest.id()).isEqualTo(untouched.id());
    assertThat(newest.refunds()).isEmpty();

    PaymentResponse older = result.get(1);
    assertThat(older.id()).isEqualTo(refunded.id());
    assertThat(older.status()).isEqualTo(PaymentStatus.PARTIALLY_REFUNDED);
    assertThat(older.amount()).isEqualTo(1000);
    assertThat(older.currency()).isEqualTo("CAD");
    assertThat(older.capturedAmount()).isEqualTo(1000);
    assertThat(older.refundedAmount()).isEqualTo(300);
    assertThat(older.refunds()).extracting(RefundResponse::id).containsExactly(refund.id());
  }

  @Test
  void merchantWithNoPaymentsGetsAnEmptyList() {
    assertThat(payments.listRecent(merchant, 10)).isEmpty();
  }

  @Test
  void unknownMerchantIsNotFound() {
    UUID unknown = UUID.randomUUID();

    assertThatThrownBy(() -> payments.listRecent(unknown, 10))
        .isInstanceOfSatisfying(
            ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.MERCHANT_NOT_FOUND));
  }

  @ParameterizedTest
  @ValueSource(ints = {0, -1, PaymentService.MAX_LIST_LIMIT + 1})
  void limitOutsideRangeIsRejected(int limit) {
    assertThatThrownBy(() -> payments.listRecent(merchant, limit))
        .isInstanceOfSatisfying(
            ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.VALIDATION_FAILED));
  }

  @Test
  void maxLimitIsAllowed() {
    pay(merchant, 100);

    assertThat(payments.listRecent(merchant, PaymentService.MAX_LIST_LIMIT)).hasSize(1);
  }
}
