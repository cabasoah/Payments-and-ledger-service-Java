package com.adomako.payments.mcp;

import com.adomako.payments.idempotency.IdempotentResponse;
import com.adomako.payments.mcp.ToolOutputs.PaymentResult;
import com.adomako.payments.mcp.ToolOutputs.PaymentView;
import com.adomako.payments.payment.PaymentCommands;
import com.adomako.payments.payment.PaymentResponse;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import java.util.UUID;
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
    UUID paymentId = arguments.requiredUuid("paymentId");
    String key = arguments.idempotencyKey();

    IdempotentResponse<PaymentResponse> result = commands.capture(key, paymentId);
    return new PaymentResult(PaymentView.from(result.body()), result.replayed());
  }
}
