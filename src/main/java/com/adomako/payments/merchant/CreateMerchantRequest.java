package com.adomako.payments.merchant;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

public record CreateMerchantRequest(@Schema(example = "Acme Coffee") @NotBlank String name) {}
