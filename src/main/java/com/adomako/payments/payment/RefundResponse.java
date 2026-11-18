package com.adomako.payments.payment;

import java.time.Instant;
import java.util.UUID;

public record RefundResponse(
    UUID id, UUID paymentId, long amount, String currency, Instant createdAt) {

  static RefundResponse from(Refund refund) {
    return new RefundResponse(
        refund.getId(),
        refund.getPaymentId(),
        refund.getAmount().minorUnits(),
        refund.getAmount().currency().getCurrencyCode(),
        refund.getCreatedAt());
  }
}
