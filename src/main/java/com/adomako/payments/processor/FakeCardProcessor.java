package com.adomako.payments.processor;

import com.adomako.payments.common.Money;
import com.adomako.payments.common.error.ApiException;
import com.adomako.payments.common.error.ErrorCode;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Stands in for a card processor. Only fake tokens exist: {@code tok_visa_ok} is approved and
 * {@code tok_declined} is declined. Real card numbers are never accepted.
 */
@Component
public class FakeCardProcessor {

  public static final String TOKEN_APPROVED = "tok_visa_ok";
  public static final String TOKEN_DECLINED = "tok_declined";

  public AuthorizationResult authorize(String cardToken, Money amount) {
    return switch (cardToken) {
      case TOKEN_APPROVED -> AuthorizationResult.approved("proc_" + UUID.randomUUID());
      case TOKEN_DECLINED -> AuthorizationResult.declined();
      default ->
          throw new ApiException(
              ErrorCode.INVALID_CARD_TOKEN,
              "Unknown card token. Use " + TOKEN_APPROVED + " or " + TOKEN_DECLINED + ".");
    };
  }
}
