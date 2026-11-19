package com.adomako.payments.idempotency;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Deletes idempotency keys older than their 24-hour lifetime, once an hour. */
@Component
public class IdempotencyKeyCleanup {

  private static final Logger log = LoggerFactory.getLogger(IdempotencyKeyCleanup.class);

  private final IdempotencyKeyRepository keys;

  public IdempotencyKeyCleanup(IdempotencyKeyRepository keys) {
    this.keys = keys;
  }

  @Scheduled(fixedDelayString = "PT1H", initialDelayString = "PT5M")
  public void purgeExpired() {
    int deleted = deleteExpired();
    if (deleted > 0) {
      log.info("Deleted {} expired idempotency keys", deleted);
    }
  }

  /** A single DELETE statement; returns how many keys were removed. */
  public int deleteExpired() {
    return keys.deleteExpired();
  }
}
