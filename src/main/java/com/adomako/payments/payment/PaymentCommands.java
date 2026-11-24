package com.adomako.payments.payment;

import com.adomako.payments.idempotency.IdempotencyService;
import com.adomako.payments.idempotency.IdempotentResponse;
import com.adomako.payments.idempotency.RequestFingerprint;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * The payment operations that move money, each run at most once per Idempotency-Key. The REST API
 * and the MCP tools both call these, so the same key and request are recognised as a retry
 * whichever way they arrive.
 */
@Service
public class PaymentCommands {

  private final PaymentService paymentService;
  private final IdempotencyService idempotency;
  private final RequestFingerprint fingerprint;

  public PaymentCommands(
      PaymentService paymentService,
      IdempotencyService idempotency,
      RequestFingerprint fingerprint) {
    this.paymentService = paymentService;
    this.idempotency = idempotency;
    this.fingerprint = fingerprint;
  }

  public IdempotentResponse<PaymentResponse> create(String key, CreatePaymentRequest request) {
    return idempotency.execute(
        request.merchantId(),
        key,
        fingerprint.of("POST", "/v1/payments", request),
        PaymentResponse.class,
        HttpStatus.CREATED.value(),
        () -> paymentService.create(request));
  }

  public IdempotentResponse<PaymentResponse> capture(String key, UUID paymentId) {
    return idempotency.execute(
        paymentService.merchantIdOf(paymentId),
        key,
        fingerprint.of("POST", "/v1/payments/" + paymentId + "/capture", null),
        PaymentResponse.class,
        HttpStatus.OK.value(),
        () -> paymentService.capture(paymentId));
  }

  public IdempotentResponse<RefundResponse> refund(
      String key, UUID paymentId, RefundRequest request) {
    return idempotency.execute(
        paymentService.merchantIdOf(paymentId),
        key,
        fingerprint.of("POST", "/v1/payments/" + paymentId + "/refunds", request),
        RefundResponse.class,
        HttpStatus.CREATED.value(),
        () -> paymentService.refund(paymentId, request));
  }
}
