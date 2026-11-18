package com.adomako.payments.payment;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import java.util.UUID;

public record CreatePaymentRequest(
    @Schema(description = "Merchant taking the payment. Replaced by the API key in a later phase.")
        @NotNull
        UUID merchantId,
    @Schema(description = "Amount in minor units (cents). 1050 CAD = 10.50 CAD.", example = "1050")
        @NotNull
        @Positive
        Long amount,
    @Schema(description = "ISO 4217 currency code.", example = "CAD")
        @NotNull
        @Pattern(regexp = "[A-Z]{3}", message = "must be a 3-letter ISO 4217 code")
        String currency,
    @Schema(
            description = "Fake card token: tok_visa_ok (approved) or tok_declined (declined).",
            example = "tok_visa_ok")
        @NotBlank
        String cardToken) {}
