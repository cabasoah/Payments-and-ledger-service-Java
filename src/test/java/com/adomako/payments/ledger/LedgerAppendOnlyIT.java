package com.adomako.payments.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.adomako.payments.AbstractIT;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Proves the database itself refuses to change ledger history. Rows are inserted with plain SQL so
 * this test depends only on the migration, not on LedgerService.
 */
class LedgerAppendOnlyIT extends AbstractIT {

  @Autowired JdbcClient jdbc;

  private UUID journalId;

  @BeforeEach
  void insertBalancedJournal() {
    TestRows rows = new TestRows(jdbc);
    UUID merchantId = rows.merchant();
    UUID paymentId = rows.payment(merchantId, "CAPTURED", 1000, 0);

    UUID receivable =
        jdbc.sql(
                """
                INSERT INTO ledger_accounts (type, merchant_id, currency)
                VALUES ('PROCESSOR_RECEIVABLE', NULL, 'CAD')
                RETURNING id
                """)
            .query(UUID.class)
            .single();
    UUID payable =
        jdbc.sql(
                """
                INSERT INTO ledger_accounts (type, merchant_id, currency)
                VALUES ('MERCHANT_PAYABLE', :merchantId, 'CAD')
                RETURNING id
                """)
            .param("merchantId", merchantId)
            .query(UUID.class)
            .single();

    journalId =
        jdbc.sql(
                """
                INSERT INTO journal_transactions (payment_id, kind)
                VALUES (:paymentId, 'CAPTURE')
                RETURNING id
                """)
            .param("paymentId", paymentId)
            .query(UUID.class)
            .single();

    insertEntry(receivable, "DEBIT");
    insertEntry(payable, "CREDIT");
  }

  private void insertEntry(UUID accountId, String direction) {
    jdbc.sql(
            """
            INSERT INTO ledger_entries
                (journal_transaction_id, account_id, direction, amount_minor, currency)
            VALUES (:journalId, :accountId, :direction, 1000, 'CAD')
            """)
        .param("journalId", journalId)
        .param("accountId", accountId)
        .param("direction", direction)
        .update();
  }

  @Test
  void insertsAreAllowed() {
    long entries =
        jdbc.sql("SELECT count(*) FROM ledger_entries WHERE journal_transaction_id = :journalId")
            .param("journalId", journalId)
            .query(Long.class)
            .single();

    assertThat(entries).isEqualTo(2);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "UPDATE ledger_entries SET amount_minor = 1 WHERE journal_transaction_id = :journalId",
        "DELETE FROM ledger_entries WHERE journal_transaction_id = :journalId",
        "UPDATE journal_transactions SET kind = 'REFUND' WHERE id = :journalId",
        "DELETE FROM journal_transactions WHERE id = :journalId",
      })
  void rejectsUpdateAndDelete(String statement) {
    assertThatThrownBy(() -> jdbc.sql(statement).param("journalId", journalId).update())
        .isInstanceOf(DataAccessException.class)
        .hasMessageContaining("append-only");
  }

  @ParameterizedTest
  @ValueSource(strings = {"TRUNCATE ledger_entries", "TRUNCATE journal_transactions CASCADE"})
  void rejectsTruncate(String statement) {
    assertThatThrownBy(() -> jdbc.sql(statement).update())
        .isInstanceOf(DataAccessException.class)
        .hasMessageContaining("append-only");
  }
}
