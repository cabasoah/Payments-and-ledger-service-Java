package com.adomako.payments;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.InstanceOfAssertFactories.MAP;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;

class HealthEndpointIT extends AbstractIT {

  @LocalServerPort int port;

  @Test
  void healthIsUpIncludingDatabase() {
    ResponseEntity<Map<String, Object>> response =
        RestClient.create("http://localhost:" + port)
            .get()
            .uri("/actuator/health")
            .retrieve()
            .toEntity(new ParameterizedTypeReference<>() {});

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).containsEntry("status", "UP");
    assertThat(response.getBody())
        .extractingByKey("components")
        .asInstanceOf(MAP)
        .extractingByKey("db")
        .asInstanceOf(MAP)
        .containsEntry("status", "UP");
  }
}
