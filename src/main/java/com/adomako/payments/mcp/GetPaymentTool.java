package com.adomako.payments.mcp;

import com.adomako.payments.payment.PaymentService;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(1)
class GetPaymentTool implements PaymentTool {

  static final String NAME = "get_payment";

  private static final Tool DEFINITION =
      ToolDefinitions.read(
          NAME,
          "Get payment",
          "Look up one payment by id. Returns its status (PENDING, AUTHORIZED, CAPTURED,"
              + " PARTIALLY_REFUNDED, REFUNDED or FAILED), the authorized amount, how much has been"
              + " captured and refunded, how much can still be refunded (refundable), and its"
              + " refunds. Every amount is integer minor units with its currency and a display"
              + " string, e.g. {amount: 1050, currency: CAD, display: 10.50 CAD}.",
          """
          {"type": "object", "properties": {%s}, "required": ["paymentId"],
           "additionalProperties": false}
          """
              .formatted(ToolDefinitions.PAYMENT_ID));

  private final PaymentService payments;

  GetPaymentTool(PaymentService payments) {
    this.payments = payments;
  }

  @Override
  public Tool definition() {
    return DEFINITION;
  }

  @Override
  public Object call(ToolArguments arguments) {
    throw new UnsupportedOperationException("not implemented yet");
  }
}
