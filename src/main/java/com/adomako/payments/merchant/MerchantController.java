package com.adomako.payments.merchant;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/merchants")
@Tag(name = "Merchants")
public class MerchantController {

  private final MerchantService merchantService;

  public MerchantController(MerchantService merchantService) {
    this.merchantService = merchantService;
  }

  @PostMapping
  @Operation(summary = "Create a merchant")
  public ResponseEntity<MerchantResponse> create(
      @Valid @RequestBody CreateMerchantRequest request) {
    MerchantResponse merchant = merchantService.create(request);
    return ResponseEntity.created(URI.create("/v1/merchants/" + merchant.id())).body(merchant);
  }
}
