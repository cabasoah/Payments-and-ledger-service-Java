package com.adomako.payments.ledger;

import java.util.Currency;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Audits the whole ledger: debits equal credits per currency, and each merchant's payable balance
 * equals captured minus refunded in the payments table.
 */
@Component
public class BalanceCheck {

  private static final String LEDGER_IMBALANCES =
      """
      SELECT currency,
             SUM(CASE WHEN direction = 'DEBIT'  THEN amount_minor ELSE 0 END)::bigint AS debits,
             SUM(CASE WHEN direction = 'CREDIT' THEN amount_minor ELSE 0 END)::bigint AS credits
      FROM ledger_entries
      GROUP BY currency
      HAVING SUM(CASE WHEN direction = 'DEBIT'  THEN amount_minor ELSE 0 END)
          <> SUM(CASE WHEN direction = 'CREDIT' THEN amount_minor ELSE 0 END)
      ORDER BY currency
      """;

  // FULL OUTER JOIN so both "payment with no ledger entries" and "entries with no matching
  // payment amounts" show up. A missing side counts as zero.
  private static final String MERCHANT_MISMATCHES =
      """
      WITH ledger AS (
          SELECT a.merchant_id, a.currency,
                 SUM(CASE e.direction WHEN 'CREDIT' THEN e.amount_minor
                                      ELSE -e.amount_minor END) AS balance
          FROM ledger_entries e
          JOIN ledger_accounts a ON a.id = e.account_id
          WHERE a.type = 'MERCHANT_PAYABLE'
          GROUP BY a.merchant_id, a.currency
      ),
      expected AS (
          SELECT merchant_id, currency, SUM(captured_minor - refunded_minor) AS balance
          FROM payments
          GROUP BY merchant_id, currency
      )
      SELECT COALESCE(l.merchant_id, x.merchant_id) AS merchant_id,
             COALESCE(l.currency, x.currency)       AS currency,
             COALESCE(l.balance, 0)::bigint         AS ledger_balance,
             COALESCE(x.balance, 0)::bigint         AS expected_balance
      FROM ledger l
      FULL OUTER JOIN expected x ON x.merchant_id = l.merchant_id AND x.currency = l.currency
      WHERE COALESCE(l.balance, 0) <> COALESCE(x.balance, 0)
      ORDER BY merchant_id, currency
      """;

  private final JdbcClient jdbc;

  public BalanceCheck(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Transactional(readOnly = true)
  public BalanceReport run() {
    List<CurrencyImbalance> imbalances =
        jdbc.sql(LEDGER_IMBALANCES)
            .query((rs, n) ->
                new CurrencyImbalance(
                    Currency.getInstance(rs.getString("currency")),
                    rs.getLong("debits"),
                    rs.getLong("credits")))
            .list();

    List<MerchantMismatch> mismatches =
        jdbc.sql(MERCHANT_MISMATCHES)
            .query((rs, n) ->
                new MerchantMismatch(
                    rs.getObject("merchant_id", UUID.class),
                    Currency.getInstance(rs.getString("currency")),
                    rs.getLong("ledger_balance"),
                    rs.getLong("expected_balance")))
            .list();

    return new BalanceReport(imbalances, mismatches);
  }
}
