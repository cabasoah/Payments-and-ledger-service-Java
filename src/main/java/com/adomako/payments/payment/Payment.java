package com.adomako.payments.payment;

import static com.adomako.payments.payment.PaymentStatus.AUTHORIZED;
import static com.adomako.payments.payment.PaymentStatus.CAPTURED;
import static com.adomako.payments.payment.PaymentStatus.FAILED;
import static com.adomako.payments.payment.PaymentStatus.PARTIALLY_REFUNDED;
import static com.adomako.payments.payment.PaymentStatus.PENDING;
import static com.adomako.payments.payment.PaymentStatus.REFUNDED;

import com.adomako.payments.common.Money;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Currency;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * The payment aggregate. Every state change goes through {@link PaymentStatus}'s transition table,
 * and all amounts are handled as {@link Money}.
 */
@Entity
@Table(name = "payments")
public class Payment {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @Column(name = "merchant_id", nullable = false)
  private UUID merchantId;

  @Column(name = "amount_minor", nullable = false)
  private long amountMinor;

  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(nullable = false, length = 3)
  private String currency;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 32)
  private PaymentStatus status;

  @Column(name = "captured_minor", nullable = false)
  private long capturedMinor;

  @Column(name = "refunded_minor", nullable = false)
  private long refundedMinor;

  @Column(name = "processor_ref")
  private String processorRef;

  @Version private Long version;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  protected Payment() {}

  public static Payment create(UUID merchantId, Money amount) {
    Objects.requireNonNull(merchantId, "merchantId");
    if (amount.minorUnits() <= 0) {
      throw new IllegalArgumentException("Payment amount must be positive: " + amount);
    }
    Payment payment = new Payment();
    payment.merchantId = merchantId;
    payment.amountMinor = amount.minorUnits();
    payment.currency = amount.currency().getCurrencyCode();
    payment.status = PENDING;
    payment.createdAt = now();
    payment.updatedAt = payment.createdAt;
    return payment;
  }

  public void authorize(String processorRef) {
    Objects.requireNonNull(processorRef, "processorRef");
    moveTo(AUTHORIZED);
    this.processorRef = processorRef;
  }

  public void decline() {
    moveTo(FAILED);
  }

  /** Full capture only: the whole authorized amount is captured. */
  public void capture() {
    moveTo(CAPTURED);
    capturedMinor = amountMinor;
  }

  /** Refunds part or all of the captured amount. Changes nothing if the refund is rejected. */
  public void refund(Money amount) {
    if (amount.minorUnits() <= 0) {
      throw new IllegalArgumentException("Refund amount must be positive: " + amount);
    }
    Money refundedAfter = getRefundedAmount().add(amount);
    PaymentStatus next = refundedAfter.equals(getCapturedAmount()) ? REFUNDED : PARTIALLY_REFUNDED;
    status.requireTransitionTo(next);
    if (refundedAfter.isGreaterThan(getCapturedAmount())) {
      Money remaining = getCapturedAmount().subtract(getRefundedAmount());
      throw new RefundExceedsCapturedException(amount, remaining);
    }
    moveTo(next);
    refundedMinor = refundedAfter.minorUnits();
  }

  private void moveTo(PaymentStatus next) {
    status.requireTransitionTo(next);
    status = next;
    updatedAt = now();
  }

  private static Instant now() {
    return Instant.now().truncatedTo(ChronoUnit.MICROS);
  }

  private Money money(long minorUnits) {
    return Money.of(minorUnits, Currency.getInstance(currency));
  }

  public UUID getId() {
    return id;
  }

  public UUID getMerchantId() {
    return merchantId;
  }

  public PaymentStatus getStatus() {
    return status;
  }

  public Money getAmount() {
    return money(amountMinor);
  }

  public Money getCapturedAmount() {
    return money(capturedMinor);
  }

  public Money getRefundedAmount() {
    return money(refundedMinor);
  }

  public String getProcessorRef() {
    return processorRef;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }
}
