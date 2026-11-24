package com.adomako.payments.ledger;

import com.adomako.payments.common.Money;
import java.time.Instant;
import java.util.UUID;

/**
 * One ledger entry as read back for a payment. {@code merchantId} is null for platform accounts
 * such as {@link AccountType#PROCESSOR_RECEIVABLE}.
 */
public record LedgerEntryView(
    UUID journalId,
    JournalKind journalKind,
    AccountType accountType,
    UUID merchantId,
    Direction direction,
    Money amount,
    Instant createdAt) {}
