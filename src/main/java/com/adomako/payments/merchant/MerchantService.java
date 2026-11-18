package com.adomako.payments.merchant;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MerchantService {

  private final MerchantRepository merchants;

  public MerchantService(MerchantRepository merchants) {
    this.merchants = merchants;
  }

  @Transactional
  public MerchantResponse create(CreateMerchantRequest request) {
    return MerchantResponse.from(merchants.save(new Merchant(request.name().strip())));
  }
}
