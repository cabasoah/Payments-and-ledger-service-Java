package com.adomako.payments.mcp;

import com.adomako.payments.idempotency.IdempotentResponse;
import com.adomako.payments.mcp.ToolOutputs.PaymentResult;
import com.adomako.payments.mcp.ToolOutputs.PaymentView;
import com.adomako.payments.payment.CreatePaymentRequest;
import com.adomako.payments.payment.PaymentCommands;
import com.adomako.payments.payment.PaymentResponse;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import jakarta.validation.Validator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(4)
@ConditionalOnProperty(name = ToolDefinitions.READ_ONLY_PROPERTY, havingValue = "false")
class CreatePaymentTool implements PaymentTool {

  static final String NAME = "create_payment";

  private static final Tool DEFINITION =
      ToolDefinitions.write(
          NAME,
          "Create payment",
          "Creates a card payment for a merchant and authorizes it with the card processor. No"
              + " money is collected until capture_payment. A declined card is not an error: the"
              + " payment is created with status FAILED. Requires idempotencyKey: retrying with the"
              + " same key and arguments returns the original payment (replayed: true) instead of"
              + " charging again.",
          """
          {"type": "object",
           "properties": {
             "merchantId": {"type": "string", "format": "uuid",
               "description": "The merchant taking the payment (a UUID)."},
             "amount": {"type": "integer", "minimum": 1,
               "description": "Amount in minor units of the currency: 1050 with CAD is 10.50 CAD. JPY has no minor unit, so 1050 JPY is 1050 yen."},
             "currency": {"type": "string", "pattern": "^[A-Z]{3}$",
               "description": "ISO 4217 currency code, e.g. CAD, USD, JPY."},
             "cardToken": {"type": "string",
               "description": "Fake card token: tok_visa_ok is approved; tok_declined is declined (the payment is still created, with status FAILED). Never send real card numbers."},
             %s
           },
           "required": ["merchantId", "amount", "currency", "cardToken", "idempotencyKey"],
           "additionalProperties": false}
          """
              .formatted(ToolDefinitions.IDEMPOTENCY_KEY),
          false);

  private final PaymentCommands commands;
  private final Validator validator;

  CreatePaymentTool(PaymentCommands commands, Validator validator) {
    this.commands = commands;
    this.validator = validator;
  }

  @Override
  public Tool definition() {
    return DEFINITION;
  }

  @Override
  public Object call(ToolArguments arguments) {
    CreatePaymentRequest request =
        new CreatePaymentRequest(
            arguments.requiredUuid("merchantId"),
            arguments.requiredLong("amount"),
            arguments.requiredString("currency"),
            arguments.requiredString("cardToken"));
    RequestValidation.requireValid(validator, request);
    String key = arguments.idempotencyKey();

    IdempotentResponse<PaymentResponse> result = commands.create(key, request);
    return new PaymentResult(PaymentView.from(result.body()), result.replayed());
  }
}
