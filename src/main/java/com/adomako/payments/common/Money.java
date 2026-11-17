package com.adomako.payments.common;

import java.util.Currency;
import java.util.Objects;

/**
 * An amount of money in minor units (e.g. cents) of an ISO-4217 currency.
 *
 * <p>Use {@link #of} to create amounts; it rejects negatives. The only way to get a negative amount
 * is {@link #subtractAllowingNegative}.
 */
public record Money(long minorUnits, Currency currency) implements Comparable<Money> {

  public Money {
    Objects.requireNonNull(currency, "currency");
    if (currency.getDefaultFractionDigits() < 0) {
      throw new IllegalArgumentException(
          "Currency has no defined decimal places: " + currency.getCurrencyCode());
    }
  }

  public static Money of(long minorUnits, Currency currency) {
    if (minorUnits < 0) {
      throw new IllegalArgumentException("Amount must not be negative: " + minorUnits);
    }
    return new Money(minorUnits, currency);
  }

  public Money add(Money other) {
    requireSameCurrency(other);
    return new Money(Math.addExact(minorUnits, other.minorUnits), currency);
  }

  public Money subtract(Money other) {
    Money result = subtractAllowingNegative(other);
    if (result.minorUnits < 0) {
      throw new IllegalArgumentException(
          "Result would be negative: " + toDisplayString() + " - " + other.toDisplayString());
    }
    return result;
  }

  public Money subtractAllowingNegative(Money other) {
    requireSameCurrency(other);
    return new Money(Math.subtractExact(minorUnits, other.minorUnits), currency);
  }

  public boolean isGreaterThan(Money other) {
    return compareTo(other) > 0;
  }

  public boolean isLessThan(Money other) {
    return compareTo(other) < 0;
  }

  @Override
  public int compareTo(Money other) {
    requireSameCurrency(other);
    return Long.compare(minorUnits, other.minorUnits);
  }

  /** Formats as "10.50 CAD", using the currency's decimal places. Independent of locale. */
  public String toDisplayString() {
    int decimals = currency.getDefaultFractionDigits();
    String code = currency.getCurrencyCode();
    if (decimals == 0) {
      return minorUnits + " " + code;
    }

    String sign = minorUnits < 0 ? "-" : "";
    String digits = Long.toString(minorUnits).replace("-", "");
    String padded = "0".repeat(Math.max(0, decimals + 1 - digits.length())) + digits;
    int point = padded.length() - decimals;
    return sign + padded.substring(0, point) + "." + padded.substring(point) + " " + code;
  }

  private void requireSameCurrency(Money other) {
    Objects.requireNonNull(other, "other");
    if (!currency.equals(other.currency)) {
      throw new CurrencyMismatchException(currency, other.currency);
    }
  }
}
