package com.adomako.payments.idempotency;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.adomako.payments.AbstractIT;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;

class IdempotencySchemaIT extends AbstractIT {

  private static final String HASH = "a".repeat(64);

  @Autowired JdbcClient jdbc;

  private void insert(
      UUID merchantId, String key, String status, Integer responseStatus, String responseBody) {
    jdbc.sql(
            """
            INSERT INTO idempotency_keys (merchant_id, idempotency_key, request_hash, status,
                                          response_status, response_body, expires_at)
            VALUES (:merchantId, :key, :hash, :status, :responseStatus,
                    CAST(:responseBody AS jsonb), now() + interval '24 hours')
            """)
        .param("merchantId", merchantId)
        .param("key", key)
        .param("hash", HASH)
        .param("status", status)
        .param("responseStatus", responseStatus)
        .param("responseBody", responseBody)
        .update();
  }

  @Test
  void acceptsInProgressAndCompletedRows() {
    UUID merchant = UUID.randomUUID();

    insert(merchant, "key-1", "IN_PROGRESS", null, null);
    insert(merchant, "key-2", "COMPLETED", 201, "{\"id\": \"x\"}");
  }

  @Test
  void rejectsDuplicateKeyForSameMerchant() {
    UUID merchant = UUID.randomUUID();
    insert(merchant, "key-1", "COMPLETED", 201, "{}");

    assertThatThrownBy(() -> insert(merchant, "key-1", "COMPLETED", 201, "{}"))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("idempotency_keys_pk");
  }

  @Test
  void allowsSameKeyForDifferentMerchants() {
    insert(UUID.randomUUID(), "key-1", "COMPLETED", 201, "{}");
    insert(UUID.randomUUID(), "key-1", "COMPLETED", 201, "{}");
  }

  @Test
  void rejectsCompletedRowWithoutResponse() {
    assertThatThrownBy(() -> insert(UUID.randomUUID(), "key-1", "COMPLETED", null, null))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("idempotency_keys_completed_has_response");
  }

  @Test
  void rejectsBlankKey() {
    assertThatThrownBy(() -> insert(UUID.randomUUID(), "  ", "IN_PROGRESS", null, null))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("idempotency_keys_key_not_blank");
  }

  @Test
  void rejectsUnknownStatus() {
    assertThatThrownBy(() -> insert(UUID.randomUUID(), "key-1", "DONE", 201, "{}"))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("idempotency_keys_status_valid");
  }

  @Test
  void rejectsExpiryNotAfterCreation() {
    assertThatThrownBy(() ->
            jdbc.sql(
                    """
                    INSERT INTO idempotency_keys (merchant_id, idempotency_key, request_hash,
                                                  status, created_at, expires_at)
                    VALUES (:merchantId, 'key-1', :hash, 'IN_PROGRESS', now(), now())
                    """)
                .param("merchantId", UUID.randomUUID())
                .param("hash", HASH)
                .update())
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("idempotency_keys_expiry_after_creation");
  }
}
