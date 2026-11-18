package com.adomako.payments.merchant;

import java.time.Instant;
import java.util.UUID;

public record MerchantResponse(UUID id, String name, Instant createdAt) {

  static MerchantResponse from(Merchant merchant) {
    return new MerchantResponse(merchant.getId(), merchant.getName(), merchant.getCreatedAt());
  }
}
