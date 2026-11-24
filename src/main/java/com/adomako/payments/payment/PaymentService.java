package com.adomako.payments.payment;

import com.adomako.payments.common.Money;
import com.adomako.payments.common.error.ApiException;
import com.adomako.payments.common.error.ErrorCode;
import com.adomako.payments.ledger.LedgerService;
import com.adomako.payments.merchant.MerchantRepository;
import com.adomako.payments.processor.AuthorizationResult;
import com.adomako.payments.processor.FakeCardProcessor;
import java.util.Currency;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * One transaction per API operation: the payment row, any refund row and the ledger journal commit
 * or roll back together. Capture and refund lock the payment row first.
 */
@Service
public class PaymentService {

  private final PaymentRepository payments;
  private final RefundRepository refunds;
  private final MerchantRepository merchants;
  private final FakeCardProcessor processor;
  private final LedgerService ledger;

  public PaymentService(
      PaymentRepository payments,
      RefundRepository refunds,
      MerchantRepository merchants,
      FakeCardProcessor processor,
      LedgerService ledger) {
    this.payments = payments;
    this.refunds = refunds;
    this.merchants = merchants;
    this.processor = processor;
    this.ledger = ledger;
  }

  /** Creates the payment and authorizes it. A decline is stored as FAILED, not an error. */
  @Transactional
  public PaymentResponse create(CreatePaymentRequest request) {
    Money amount = Money.of(request.amount(), supportedCurrency(request.currency()));
    requireMerchant(request.merchantId());

    Payment payment = Payment.create(request.merchantId(), amount);
    AuthorizationResult result = processor.authorize(request.cardToken(), amount);
    if (result.approved()) {
      payment.authorize(result.processorRef());
    } else {
      payment.decline();
    }
    return PaymentResponse.from(payments.save(payment), List.of());
  }

  @Transactional
  public PaymentResponse capture(UUID paymentId) {
    Payment payment = lockPayment(paymentId);
    payment.capture();
    ledger.recordCapture(payment.getId(), payment.getMerchantId(), payment.getCapturedAmount());
    return PaymentResponse.from(payment, refunds.findByPaymentIdOrderByCreatedAtAsc(paymentId));
  }

  @Transactional
  public RefundResponse refund(UUID paymentId, RefundRequest request) {
    Payment payment = lockPayment(paymentId);
    Money amount = Money.of(request.amount(), payment.getAmount().currency());
    payment.refund(amount);
    Refund refund = refunds.save(new Refund(payment.getId(), amount));
    ledger.recordRefund(payment.getId(), payment.getMerchantId(), amount);
    return RefundResponse.from(refund);
  }

  /** The merchant that owns this payment; idempotency keys are scoped to it. */
  @Transactional(readOnly = true)
  public UUID merchantIdOf(UUID paymentId) {
    return payments
        .findById(paymentId)
        .map(Payment::getMerchantId)
        .orElseThrow(() -> notFound(paymentId));
  }

  @Transactional(readOnly = true)
  public PaymentResponse get(UUID paymentId) {
    Payment payment = payments.findById(paymentId).orElseThrow(() -> notFound(paymentId));
    return PaymentResponse.from(payment, refunds.findByPaymentIdOrderByCreatedAtAsc(paymentId));
  }

  /** Largest page {@link #listRecent} returns. */
  public static final int MAX_LIST_LIMIT = 100;

  /**
   * The merchant's most recent payments, newest first, each with its refunds.
   *
   * @param limit 1 to {@link #MAX_LIST_LIMIT}
   */
  @Transactional(readOnly = true)
  public List<PaymentResponse> listRecent(UUID merchantId, int limit) {
    if (limit < 1 || limit > MAX_LIST_LIMIT) {
      throw new ApiException(
          ErrorCode.VALIDATION_FAILED, "limit must be between 1 and " + MAX_LIST_LIMIT + ".");
    }
    requireMerchant(merchantId);

    List<Payment> page =
        payments.findByMerchantIdOrderByCreatedAtDescIdDesc(merchantId, Limit.of(limit));
    if (page.isEmpty()) {
      return List.of();
    }
    List<UUID> ids = page.stream().map(Payment::getId).toList();
    Map<UUID, List<Refund>> refundsByPayment =
        refunds.findByPaymentIdInOrderByCreatedAtAsc(ids).stream()
            .collect(Collectors.groupingBy(Refund::getPaymentId));
    return page.stream()
        .map(p -> PaymentResponse.from(p, refundsByPayment.getOrDefault(p.getId(), List.of())))
        .toList();
  }

  private void requireMerchant(UUID merchantId) {
    if (!merchants.existsById(merchantId)) {
      throw new ApiException(
          ErrorCode.MERCHANT_NOT_FOUND, "Merchant " + merchantId + " does not exist.");
    }
  }

  private Payment lockPayment(UUID paymentId) {
    return payments.findByIdForUpdate(paymentId).orElseThrow(() -> notFound(paymentId));
  }

  private static ApiException notFound(UUID paymentId) {
    return new ApiException(
        ErrorCode.PAYMENT_NOT_FOUND, "Payment " + paymentId + " does not exist.");
  }

  /** A real ISO 4217 currency with defined decimal places (rejects e.g. ABC and XAU). */
  private static Currency supportedCurrency(String code) {
    try {
      Currency currency = Currency.getInstance(code);
      if (currency.getDefaultFractionDigits() < 0) {
        throw new IllegalArgumentException(code);
      }
      return currency;
    } catch (IllegalArgumentException e) {
      throw new ApiException(ErrorCode.VALIDATION_FAILED, "Unsupported currency: " + code + ".");
    }
  }
}
