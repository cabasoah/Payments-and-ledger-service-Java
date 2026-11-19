package com.adomako.payments.idempotency;

import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Plain SQL access to idempotency_keys. All methods run in the caller's transaction. */
@Repository
public class IdempotencyKeyRepository {

  /** A stored key as seen by the current transaction. */
  public record StoredKey(
      String requestHash,
      String status,
      Integer responseStatus,
      String responseBody,
      boolean expired) {}

  private final JdbcClient jdbc;

  public IdempotencyKeyRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * Takes a transaction-scoped advisory lock for this merchant and key without waiting. Returns
   * false if another transaction holds it. Released automatically at commit or rollback.
   */
  public boolean tryLock(UUID merchantId, String key) {
    return jdbc.sql(
            """
            SELECT pg_try_advisory_xact_lock(
                hashtextextended(CAST(:merchantId AS text) || ':' || :key, 0))
            """)
        .param("merchantId", merchantId)
        .param("key", key)
        .query(Boolean.class)
        .single();
  }

  public Optional<StoredKey> find(UUID merchantId, String key) {
    return jdbc.sql(
            """
            SELECT request_hash, status, response_status, CAST(response_body AS text) AS body,
                   expires_at <= now() AS expired
            FROM idempotency_keys
            WHERE merchant_id = :merchantId AND idempotency_key = :key
            """)
        .param("merchantId", merchantId)
        .param("key", key)
        .query((rs, n) ->
            new StoredKey(
                rs.getString("request_hash"),
                rs.getString("status"),
                rs.getObject("response_status", Integer.class),
                rs.getString("body"),
                rs.getBoolean("expired")))
        .optional();
  }

  public void delete(UUID merchantId, String key) {
    jdbc.sql(
            "DELETE FROM idempotency_keys WHERE merchant_id = :merchantId AND idempotency_key = :key")
        .param("merchantId", merchantId)
        .param("key", key)
        .update();
  }

  public void insertInProgress(UUID merchantId, String key, String requestHash) {
    jdbc.sql(
            """
            INSERT INTO idempotency_keys (merchant_id, idempotency_key, request_hash, status,
                                          expires_at)
            VALUES (:merchantId, :key, :hash, 'IN_PROGRESS', now() + interval '24 hours')
            """)
        .param("merchantId", merchantId)
        .param("key", key)
        .param("hash", requestHash)
        .update();
  }

  public void complete(UUID merchantId, String key, int responseStatus, String responseBody) {
    jdbc.sql(
            """
            UPDATE idempotency_keys
            SET status = 'COMPLETED', response_status = :status,
                response_body = CAST(:body AS jsonb)
            WHERE merchant_id = :merchantId AND idempotency_key = :key
            """)
        .param("merchantId", merchantId)
        .param("key", key)
        .param("status", responseStatus)
        .param("body", responseBody)
        .update();
  }

  public int deleteExpired() {
    return jdbc.sql("DELETE FROM idempotency_keys WHERE expires_at <= now()").update();
  }
}
