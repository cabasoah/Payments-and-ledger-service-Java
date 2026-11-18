package com.adomako.payments.ledger;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** A set of postings recorded together for one payment event. */
public record Journal(UUID paymentId, JournalKind kind, List<Posting> postings) {

  public Journal {
    Objects.requireNonNull(paymentId, "paymentId");
    Objects.requireNonNull(kind, "kind");
    postings = List.copyOf(postings);
  }
}
