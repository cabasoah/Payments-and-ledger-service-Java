package com.adomako.payments.mcp;

import com.adomako.payments.idempotency.IdempotentResponse;
import com.adomako.payments.mcp.ToolOutputs.PaymentView;
import com.adomako.payments.mcp.ToolOutputs.RefundResult;
import com.adomako.payments.mcp.ToolOutputs.RefundView;
import com.adomako.payments.payment.PaymentCommands;
import com.adomako.payments.payment.PaymentResponse;
import com.adomako.payments.payment.PaymentService;
import com.adomako.payments.payment.RefundRequest;
import com.adomako.payments.payment.RefundResponse;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import jakarta.validation.Validator;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(6)
@ConditionalOnProperty(name = ToolDefinitions.READ_ONLY_PROPERTY, havingValue = "false")
class RefundPaymentTool implements PaymentTool {

  static final String NAME = "refund_payment";

  private static final Tool DEFINITION =
      ToolDefinitions.write(
          NAME,
          "Refund payment",
          "Refunds part or all of a captured payment back to the customer. amount is in minor"
              + " units of the payment's currency and must not exceed what is still refundable"
              + " (captured minus already refunded; get_payment shows it as refundable). If it"
              + " does, the call fails with REFUND_EXCEEDS_CAPTURED and nothing changes. Returns"
              + " the refund and the payment after it. Requires idempotencyKey: use a new key for"
              + " each new refund, and the same key only to retry the same refund.",
          """
          {"type": "object",
           "properties": {
             %s,
             "amount": {"type": "integer", "minimum": 1,
               "description": "Amount to refund in minor units of the payment's currency: 300 with a CAD payment is 3.00 CAD."},
             %s
           },
           "required": ["paymentId", "amount", "idempotencyKey"], "additionalProperties": false}
          """
              .formatted(ToolDefinitions.PAYMENT_ID, ToolDefinitions.IDEMPOTENCY_KEY),
          true);

  private final PaymentCommands commands;
  private final PaymentService payments;
  private final Validator validator;

  RefundPaymentTool(PaymentCommands commands, PaymentService payments, Validator validator) {
    this.commands = commands;
    this.payments = payments;
    this.validator = validator;
  }

  @Override
  public Tool definition() {
    return DEFINITION;
  }

  @Override
  public Object call(ToolArguments arguments) {
    UUID paymentId = arguments.requiredUuid("paymentId");
    RefundRequest request = new RefundRequest(arguments.requiredLong("amount"));
    RequestValidation.requireValid(validator, request);
    String key = arguments.idempotencyKey();

    IdempotentResponse<RefundResponse> result = commands.refund(key, paymentId, request);
    PaymentResponse after = payments.get(paymentId);
    return new RefundResult(
        RefundView.from(result.body()), PaymentView.from(after), result.replayed());
  }
}
