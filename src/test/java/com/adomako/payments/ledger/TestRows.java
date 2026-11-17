package com.adomako.payments.ledger;

import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Inserts merchant and payment rows with plain SQL so ledger tests have something to post for. */
final class TestRows {

  private final JdbcClient jdbc;

  TestRows(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  UUID merchant() {
    UUID id = UUID.randomUUID();
    jdbc.sql("INSERT INTO merchants (id, name) VALUES (:id, 'Ledger Test Merchant')")
        .param("id", id)
        .update();
    return id;
  }

  /** A CAD payment of 1000 minor units with the given status and amounts. */
  UUID payment(UUID merchantId, String status, long captured, long refunded) {
    UUID id = UUID.randomUUID();
    jdbc.sql(
            """
            INSERT INTO payments (id, merchant_id, amount_minor, currency, status,
                                  captured_minor, refunded_minor)
            VALUES (:id, :merchantId, 1000, 'CAD', :status, :captured, :refunded)
            """)
        .param("id", id)
        .param("merchantId", merchantId)
        .param("status", status)
        .param("captured", captured)
        .param("refunded", refunded)
        .update();
    return id;
  }
}
