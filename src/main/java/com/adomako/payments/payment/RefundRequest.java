package com.adomako.payments.payment;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record RefundRequest(
    @Schema(
            description = "Amount to refund in minor units, in the payment's currency.",
            example = "300")
        @NotNull
        @Positive
        Long amount) {}
