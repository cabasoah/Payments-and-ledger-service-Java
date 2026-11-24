package com.adomako.payments.mcp;

import com.adomako.payments.ledger.LedgerService;
import com.adomako.payments.payment.PaymentService;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(3)
class GetLedgerBalanceTool implements PaymentTool {

  static final String NAME = "get_ledger_balance";

  private static final Tool DEFINITION =
      ToolDefinitions.read(
          NAME,
          "Get ledger balance for a payment",
          "Show the double-entry ledger entries recorded for one payment, and check them. Returns"
              + " each entry (journal CAPTURE or REFUND, account, DEBIT or CREDIT, amount); debit"
              + " and credit totals per currency; balanced (true when debits equal credits in every"
              + " currency); and matchesPayment (true when what we owe the merchant for this"
              + " payment equals captured minus refunded). A payment that is not captured yet has"
              + " no entries. Use it after capture_payment or refund_payment to verify the books.",
          """
          {"type": "object", "properties": {%s}, "required": ["paymentId"],
           "additionalProperties": false}
          """
              .formatted(ToolDefinitions.PAYMENT_ID));

  private final PaymentService payments;
  private final LedgerService ledger;

  GetLedgerBalanceTool(PaymentService payments, LedgerService ledger) {
    this.payments = payments;
    this.ledger = ledger;
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
