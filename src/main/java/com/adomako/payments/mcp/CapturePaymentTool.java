package com.adomako.payments.mcp;

import com.adomako.payments.payment.PaymentCommands;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(5)
@ConditionalOnProperty(name = ToolDefinitions.READ_ONLY_PROPERTY, havingValue = "false")
class CapturePaymentTool implements PaymentTool {

  static final String NAME = "capture_payment";

  private static final Tool DEFINITION =
      ToolDefinitions.write(
          NAME,
          "Capture payment",
          "Captures the full authorized amount of an AUTHORIZED payment: the money is collected"
              + " and recorded in the ledger. A payment can be captured only once, and only while"
              + " AUTHORIZED. Requires idempotencyKey; if unsure whether a capture worked, retry"
              + " with the same key.",
          """
          {"type": "object", "properties": {%s, %s},
           "required": ["paymentId", "idempotencyKey"], "additionalProperties": false}
          """
              .formatted(ToolDefinitions.PAYMENT_ID, ToolDefinitions.IDEMPOTENCY_KEY),
          true);

  private final PaymentCommands commands;

  CapturePaymentTool(PaymentCommands commands) {
    this.commands = commands;
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
