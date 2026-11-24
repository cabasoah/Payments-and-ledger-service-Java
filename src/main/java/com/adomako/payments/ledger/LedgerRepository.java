package com.adomako.payments.ledger;

import com.adomako.payments.common.Money;
import java.time.OffsetDateTime;
import java.util.Currency;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Insert-only access to the ledger tables. There are deliberately no update or delete methods. */
@Repository
public class LedgerRepository {

  private final JdbcClient jdbc;

  public LedgerRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /** Returns the account for this type, owner and currency, creating it on first use. */
  public UUID accountId(AccountType type, UUID merchantId, Currency currency) {
    jdbc.sql(
            """
            INSERT INTO ledger_accounts (type, merchant_id, currency)
            VALUES (:type, CAST(:merchantId AS uuid), :currency)
            ON CONFLICT ON CONSTRAINT ledger_accounts_unique DO NOTHING
            """)
        .param("type", type.name())
        .param("merchantId", merchantId)
        .param("currency", currency.getCurrencyCode())
        .update();

    return jdbc.sql(
            """
            SELECT id FROM ledger_accounts
            WHERE type = :type
              AND merchant_id IS NOT DISTINCT FROM CAST(:merchantId AS uuid)
              AND currency = :currency
            """)
        .param("type", type.name())
        .param("merchantId", merchantId)
        .param("currency", currency.getCurrencyCode())
        .query(UUID.class)
        .single();
  }

  /** The payment's entries, oldest journal first. */
  public List<LedgerEntryView> entriesForPayment(UUID paymentId) {
    return jdbc.sql(
            """
            SELECT j.id AS journal_id, j.kind, a.type, a.merchant_id,
                   e.direction, e.amount_minor, e.currency, e.created_at
            FROM ledger_entries e
            JOIN journal_transactions j ON j.id = e.journal_transaction_id
            JOIN ledger_accounts a ON a.id = e.account_id
            WHERE j.payment_id = :paymentId
            ORDER BY j.created_at, j.id, e.created_at, e.id
            """)
        .param("paymentId", paymentId)
        .query((rs, n) ->
            new LedgerEntryView(
                rs.getObject("journal_id", UUID.class),
                JournalKind.valueOf(rs.getString("kind")),
                AccountType.valueOf(rs.getString("type")),
                rs.getObject("merchant_id", UUID.class),
                Direction.valueOf(rs.getString("direction")),
                Money.of(
                    rs.getLong("amount_minor"), Currency.getInstance(rs.getString("currency"))),
                rs.getObject("created_at", OffsetDateTime.class).toInstant()))
        .list();
  }

  public void insert(Journal journal) {
    UUID journalId =
        jdbc.sql(
                """
                INSERT INTO journal_transactions (payment_id, kind)
                VALUES (:paymentId, :kind)
                RETURNING id
                """)
            .param("paymentId", journal.paymentId())
            .param("kind", journal.kind().name())
            .query(UUID.class)
            .single();

    for (Posting posting : journal.postings()) {
      jdbc.sql(
              """
              INSERT INTO ledger_entries
                  (journal_transaction_id, account_id, direction, amount_minor, currency)
              VALUES (:journalId, :accountId, :direction, :amount, :currency)
              """)
          .param("journalId", journalId)
          .param("accountId", posting.accountId())
          .param("direction", posting.direction().name())
          .param("amount", posting.amount().minorUnits())
          .param("currency", posting.amount().currency().getCurrencyCode())
          .update();
    }
  }
}
