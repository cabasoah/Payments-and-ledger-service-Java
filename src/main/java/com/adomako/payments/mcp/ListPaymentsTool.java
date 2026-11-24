package com.adomako.payments.mcp;

import com.adomako.payments.mcp.ToolOutputs.PaymentList;
import com.adomako.payments.mcp.ToolOutputs.PaymentView;
import com.adomako.payments.payment.PaymentService;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import java.util.List;
import java.util.UUID;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(2)
class ListPaymentsTool implements PaymentTool {

  static final String NAME = "list_payments";
  static final int DEFAULT_LIMIT = 20;

  private static final Tool DEFINITION =
      ToolDefinitions.read(
          NAME,
          "List payments",
          "List a merchant's most recent payments, newest first, with the same fields as"
              + " get_payment. Use it to find a payment's id. limit defaults to "
              + DEFAULT_LIMIT
              + " and can be at most "
              + PaymentService.MAX_LIST_LIMIT
              + ".",
          """
          {"type": "object",
           "properties": {
             "merchantId": {"type": "string", "format": "uuid",
               "description": "The merchant's id (a UUID)."},
             "limit": {"type": "integer", "minimum": 1, "maximum": %d, "default": %d,
               "description": "How many payments to return."}
           },
           "required": ["merchantId"], "additionalProperties": false}
          """
              .formatted(PaymentService.MAX_LIST_LIMIT, DEFAULT_LIMIT));

  private final PaymentService payments;

  ListPaymentsTool(PaymentService payments) {
    this.payments = payments;
  }

  @Override
  public Tool definition() {
    return DEFINITION;
  }

  @Override
  public Object call(ToolArguments arguments) {
    UUID merchantId = arguments.requiredUuid("merchantId");
    int limit = arguments.optionalInt("limit", DEFAULT_LIMIT);
    List<PaymentView> recent =
        payments.listRecent(merchantId, limit).stream().map(PaymentView::from).toList();
    return new PaymentList(merchantId, recent.size(), recent);
  }
}
