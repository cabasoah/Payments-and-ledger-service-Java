package com.adomako.payments.payment;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record PaymentResponse(
    UUID id,
    UUID merchantId,
    PaymentStatus status,
    long amount,
    String currency,
    long capturedAmount,
    long refundedAmount,
    String processorRef,
    List<RefundResponse> refunds,
    Instant createdAt,
    Instant updatedAt) {

  static PaymentResponse from(Payment payment, List<Refund> refunds) {
    return new PaymentResponse(
        payment.getId(),
        payment.getMerchantId(),
        payment.getStatus(),
        payment.getAmount().minorUnits(),
        payment.getAmount().currency().getCurrencyCode(),
        payment.getCapturedAmount().minorUnits(),
        payment.getRefundedAmount().minorUnits(),
        payment.getProcessorRef(),
        refunds.stream().map(RefundResponse::from).toList(),
        payment.getCreatedAt(),
        payment.getUpdatedAt());
  }
}
