package com.adomako.payments.common;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

  @Bean
  OpenAPI paymentsLedgerOpenApi() {
    return new OpenAPI()
        .info(
            new Info()
                .title("Payments Ledger API")
                .version("v1")
                .description(
                    "Merchant payments with a double-entry ledger. Amounts are integer minor"
                        + " units (cents) plus an ISO 4217 currency code. Errors are RFC 7807"
                        + " problem+json with a machine-readable `code`."));
  }
}
