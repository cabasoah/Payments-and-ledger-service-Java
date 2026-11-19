package com.adomako.payments.idempotency;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * SHA-256 of the method, path and request body. The body is turned into JSON with keys sorted, so
 * whitespace and field order in the original request don't change the fingerprint.
 */
@Component
public class RequestFingerprint {

  private final JsonMapper jsonMapper;

  public RequestFingerprint(JsonMapper jsonMapper) {
    this.jsonMapper = jsonMapper;
  }

  public String of(String method, String path, Object body) {
    String canonicalBody =
        body == null
            ? ""
            : jsonMapper.writeValueAsString(sorted(jsonMapper.convertValue(body, Object.class)));
    return sha256(method + "\n" + path + "\n" + canonicalBody);
  }

  /** Recursively replaces maps with key-sorted maps. */
  private static Object sorted(Object value) {
    if (value instanceof Map<?, ?> map) {
      Map<String, Object> result = new TreeMap<>();
      map.forEach((k, v) -> result.put(String.valueOf(k), sorted(v)));
      return result;
    }
    if (value instanceof List<?> list) {
      return list.stream().map(RequestFingerprint::sorted).toList();
    }
    return value;
  }

  private static String sha256(String input) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(digest.digest(input.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is always available in the JDK", e);
    }
  }
}
