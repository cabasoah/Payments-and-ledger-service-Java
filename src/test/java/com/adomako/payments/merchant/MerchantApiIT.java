package com.adomako.payments.merchant;

import static org.assertj.core.api.Assertions.assertThat;

import com.adomako.payments.AbstractApiIT;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

class MerchantApiIT extends AbstractApiIT {

  @Test
  void createsMerchant() {
    ResponseEntity<MerchantResponse> response =
        http.post()
            .uri("/v1/merchants")
            .contentType(MediaType.APPLICATION_JSON)
            .body(Map.of("name", "Acme Coffee"))
            .retrieve()
            .toEntity(MerchantResponse.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    MerchantResponse merchant = response.getBody();
    assertThat(merchant.id()).isNotNull();
    assertThat(merchant.name()).isEqualTo("Acme Coffee");
    assertThat(merchant.createdAt()).isNotNull();
    assertThat(response.getHeaders().getLocation()).hasPath("/v1/merchants/" + merchant.id());
  }

  @ParameterizedTest
  @NullSource
  @ValueSource(strings = {"", "   "})
  void rejectsBlankName(String name) {
    Map<String, Object> body = new HashMap<>();
    body.put("name", name);

    ResponseEntity<Map<String, Object>> response = postForMap("/v1/merchants", body);

    assertProblem(response, HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "/v1/merchants");
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> errors = (List<Map<String, Object>>) response.getBody().get("errors");
    assertThat(errors).extracting(e -> e.get("field")).containsExactly("name");
  }
}
