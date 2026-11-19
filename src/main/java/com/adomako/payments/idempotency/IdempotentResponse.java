package com.adomako.payments.idempotency;

/** The result of an idempotent operation: either just produced, or replayed from storage. */
public record IdempotentResponse<T>(int status, T body, boolean replayed) {}
