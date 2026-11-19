package com.adomako.payments.idempotency;

import static org.assertj.core.api.Assertions.assertThat;

import com.adomako.payments.AbstractIT;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

class IdempotencyKeyCleanupIT extends AbstractIT {

  @Autowired JdbcClient jdbc;
  @Autowired IdempotencyKeyCleanup cleanup;

  /** Inserts a completed key created {@code createdAgo} ago that expires in {@code expiresIn}. */
  private void insertKey(UUID merchantId, String key, String createdAgo, String expiresIn) {
    jdbc.sql(
            """
            INSERT INTO idempotency_keys (merchant_id, idempotency_key, request_hash, status,
                                          response_status, response_body, created_at, expires_at)
            VALUES (:merchantId, :key, :hash, 'COMPLETED', 201, CAST('{}' AS jsonb),
                    now() - CAST(:createdAgo AS interval), now() + CAST(:expiresIn AS interval))
            """)
        .param("merchantId", merchantId)
        .param("key", key)
        .param("hash", "b".repeat(64))
        .param("createdAgo", createdAgo)
        .param("expiresIn", expiresIn)
        .update();
  }

  private boolean exists(UUID merchantId, String key) {
    long count =
        jdbc.sql(
                """
                SELECT count(*) FROM idempotency_keys
                WHERE merchant_id = :merchantId AND idempotency_key = :key
                """)
            .param("merchantId", merchantId)
            .param("key", key)
            .query(Long.class)
            .single();
    return count == 1;
  }

  @Test
  void deletesExpiredKeysAndKeepsLiveOnes() {
    UUID merchant = UUID.randomUUID();
    insertKey(merchant, "expired", "25 hours", "-1 hour");
    insertKey(merchant, "live", "1 hour", "23 hours");

    int deleted = cleanup.deleteExpired();

    assertThat(deleted).isGreaterThanOrEqualTo(1);
    assertThat(exists(merchant, "expired")).isFalse();
    assertThat(exists(merchant, "live")).isTrue();
  }
}
