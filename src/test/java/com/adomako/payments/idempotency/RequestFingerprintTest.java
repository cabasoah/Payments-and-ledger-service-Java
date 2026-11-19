package com.adomako.payments.idempotency;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class RequestFingerprintTest {

  private final RequestFingerprint fingerprint =
      new RequestFingerprint(JsonMapper.builder().build());

  record Body(long amount, String currency) {}

  @Test
  void isAHexSha256() {
    assertThat(fingerprint.of("POST", "/v1/payments", new Body(1000, "CAD")))
        .hasSize(64)
        .matches("[0-9a-f]{64}");
  }

  @Test
  void sameRequestGivesSameFingerprint() {
    assertThat(fingerprint.of("POST", "/v1/payments", new Body(1000, "CAD")))
        .isEqualTo(fingerprint.of("POST", "/v1/payments", new Body(1000, "CAD")));
  }

  @Test
  void fieldOrderDoesNotMatter() {
    Map<String, Object> ab = new LinkedHashMap<>();
    ab.put("amount", 1000);
    ab.put("currency", "CAD");
    Map<String, Object> ba = new LinkedHashMap<>();
    ba.put("currency", "CAD");
    ba.put("amount", 1000);

    assertThat(fingerprint.of("POST", "/v1/payments", ab))
        .isEqualTo(fingerprint.of("POST", "/v1/payments", ba));
  }

  @Test
  void differentBodyGivesDifferentFingerprint() {
    assertThat(fingerprint.of("POST", "/v1/payments", new Body(1000, "CAD")))
        .isNotEqualTo(fingerprint.of("POST", "/v1/payments", new Body(2000, "CAD")));
  }

  @Test
  void differentPathGivesDifferentFingerprint() {
    Body body = new Body(300, "CAD");

    assertThat(fingerprint.of("POST", "/v1/payments/a/refunds", body))
        .isNotEqualTo(fingerprint.of("POST", "/v1/payments/b/refunds", body));
  }

  @Test
  void differentMethodGivesDifferentFingerprint() {
    Body body = new Body(300, "CAD");

    assertThat(fingerprint.of("POST", "/v1/payments", body))
        .isNotEqualTo(fingerprint.of("PUT", "/v1/payments", body));
  }

  @Test
  void requestWithoutBodyHasAFingerprint() {
    assertThat(fingerprint.of("POST", "/v1/payments/a/capture", null))
        .hasSize(64)
        .isNotEqualTo(fingerprint.of("POST", "/v1/payments/b/capture", null));
  }
}
