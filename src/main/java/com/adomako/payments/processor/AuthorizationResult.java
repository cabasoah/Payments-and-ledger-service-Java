package com.adomako.payments.processor;

/** The processor's answer to an authorization: approved with a reference, or declined. */
public record AuthorizationResult(boolean approved, String processorRef) {

  public static AuthorizationResult approved(String processorRef) {
    return new AuthorizationResult(true, processorRef);
  }

  public static AuthorizationResult declined() {
    return new AuthorizationResult(false, null);
  }
}
