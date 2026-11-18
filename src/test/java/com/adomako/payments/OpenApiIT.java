package com.adomako.payments;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

class OpenApiIT extends AbstractApiIT {

  @Test
  void documentsEveryOperation() {
    ResponseEntity<String> docs = http.get().uri("/v3/api-docs").retrieve().toEntity(String.class);

    assertThat(docs.getStatusCode().is2xxSuccessful()).isTrue();
    assertThat(docs.getBody())
        .contains(
            "\"/v1/merchants\"",
            "\"/v1/payments\"",
            "\"/v1/payments/{id}\"",
            "\"/v1/payments/{id}/capture\"",
            "\"/v1/payments/{id}/refunds\"");
  }

  @Test
  void createPaymentHasRequestExampleAndProblemResponses() {
    String docs = http.get().uri("/v3/api-docs").retrieve().body(String.class);

    assertThat(docs).contains("tok_visa_ok", "REFUND_EXCEEDS_CAPTURED", "application/problem+json");
  }

  @Test
  void swaggerUiIsServed() {
    ResponseEntity<String> page =
        http.get().uri("/swagger-ui.html").retrieve().toEntity(String.class);

    assertThat(page.getStatusCode().is2xxSuccessful() || page.getStatusCode().is3xxRedirection())
        .isTrue();
  }
}
