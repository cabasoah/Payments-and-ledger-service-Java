package com.adomako.payments.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Currency;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class MoneyTest {

  private static final Currency CAD = Currency.getInstance("CAD");
  private static final Currency USD = Currency.getInstance("USD");
  private static final Currency JPY = Currency.getInstance("JPY");
  private static final Currency BHD = Currency.getInstance("BHD");

  private static Money cad(long minorUnits) {
    return Money.of(minorUnits, CAD);
  }

  @Nested
  class Creation {

    @Test
    void rejectsNullCurrency() {
      assertThatThrownBy(() -> Money.of(100, null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void rejectsNegativeAmount() {
      assertThatThrownBy(() -> Money.of(-1, CAD))
          .isExactlyInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsCurrencyWithoutDefinedDecimalPlaces() {
      Currency gold = Currency.getInstance("XAU");

      assertThatThrownBy(() -> Money.of(100, gold))
          .isExactlyInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void allowsZero() {
      assertThat(cad(0).minorUnits()).isZero();
    }

    @Test
    void exposesMinorUnitsAndCurrency() {
      Money money = Money.of(1050, CAD);

      assertThat(money.minorUnits()).isEqualTo(1050);
      assertThat(money.currency()).isEqualTo(CAD);
    }
  }

  @Nested
  class Add {

    @Test
    void addsSameCurrency() {
      assertThat(cad(1050).add(cad(250))).isEqualTo(cad(1300));
    }

    @Test
    void doesNotModifyOperands() {
      Money a = cad(1050);
      Money b = cad(250);

      a.add(b);

      assertThat(a).isEqualTo(cad(1050));
      assertThat(b).isEqualTo(cad(250));
    }

    @Test
    void rejectsNull() {
      assertThatThrownBy(() -> cad(1050).add(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void throwsOnOverflow() {
      assertThatThrownBy(() -> cad(Long.MAX_VALUE).add(cad(1)))
          .isInstanceOf(ArithmeticException.class);
    }
  }

  @Nested
  class Subtract {

    @Test
    void subtractsSameCurrency() {
      assertThat(cad(1050).subtract(cad(250))).isEqualTo(cad(800));
    }

    @Test
    void allowsZeroResult() {
      assertThat(cad(1050).subtract(cad(1050))).isEqualTo(cad(0));
    }

    @Test
    void rejectsNegativeResult() {
      assertThatThrownBy(() -> cad(250).subtract(cad(1050)))
          .isExactlyInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNull() {
      assertThatThrownBy(() -> cad(1050).subtract(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void subtractAllowingNegativeReturnsNegativeResult() {
      Money result = cad(250).subtractAllowingNegative(cad(1050));

      assertThat(result.minorUnits()).isEqualTo(-800);
      assertThat(result.currency()).isEqualTo(CAD);
    }

    @Test
    void subtractAllowingNegativeThrowsOnOverflow() {
      Money mostNegative = cad(0).subtractAllowingNegative(cad(Long.MAX_VALUE));

      assertThatThrownBy(() -> mostNegative.subtractAllowingNegative(cad(2)))
          .isInstanceOf(ArithmeticException.class);
    }
  }

  @Nested
  class CurrencyMismatch {

    static Stream<Arguments> operations() {
      return Stream.of(
          op("add", Money::add),
          op("subtract", Money::subtract),
          op("subtractAllowingNegative", Money::subtractAllowingNegative),
          op("isGreaterThan", Money::isGreaterThan),
          op("isLessThan", Money::isLessThan),
          op("compareTo", Money::compareTo));
    }

    private static Arguments op(String name, BiConsumer<Money, Money> operation) {
      return Arguments.of(Named.of(name, operation));
    }

    @ParameterizedTest
    @MethodSource("operations")
    void throwsWhenCurrenciesDiffer(BiConsumer<Money, Money> operation) {
      Money cad = Money.of(1050, CAD);
      Money usd = Money.of(1050, USD);

      assertThatThrownBy(() -> operation.accept(cad, usd))
          .isInstanceOf(CurrencyMismatchException.class)
          .hasMessageContaining("CAD")
          .hasMessageContaining("USD");
    }

    @Test
    void isAnIllegalArgumentException() {
      assertThat(IllegalArgumentException.class).isAssignableFrom(CurrencyMismatchException.class);
    }
  }

  @Nested
  class Comparison {

    @Test
    void isGreaterThan() {
      assertThat(cad(1050).isGreaterThan(cad(250))).isTrue();
      assertThat(cad(250).isGreaterThan(cad(1050))).isFalse();
      assertThat(cad(1050).isGreaterThan(cad(1050))).isFalse();
    }

    @Test
    void isLessThan() {
      assertThat(cad(250).isLessThan(cad(1050))).isTrue();
      assertThat(cad(1050).isLessThan(cad(250))).isFalse();
      assertThat(cad(1050).isLessThan(cad(1050))).isFalse();
    }

    @Test
    void compareToOrdersByAmount() {
      assertThat(cad(250).compareTo(cad(1050))).isNegative();
      assertThat(cad(1050).compareTo(cad(1050))).isZero();
      assertThat(cad(1050).compareTo(cad(250))).isPositive();
    }

    @Test
    void sortsAscending() {
      List<Money> amounts = new ArrayList<>(List.of(cad(1050), cad(0), cad(250)));

      Collections.sort(amounts);

      assertThat(amounts).containsExactly(cad(0), cad(250), cad(1050));
    }
  }

  @Nested
  class Equality {

    @Test
    void sameAmountAndCurrencyAreEqualWithSameHashCode() {
      Money a = Money.of(1050, CAD);
      Money b = Money.of(1050, CAD);

      assertThat(a).isEqualTo(b);
      assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    void differentAmountsAreNotEqual() {
      assertThat(cad(1050)).isNotEqualTo(cad(1051));
    }

    @Test
    void sameAmountInDifferentCurrencyIsNotEqual() {
      assertThat(Money.of(1050, CAD)).isNotEqualTo(Money.of(1050, USD));
    }

    @Test
    void isNotEqualToNullOrOtherTypes() {
      assertThat(cad(1050)).isNotEqualTo(null);
      assertThat(cad(1050)).isNotEqualTo("10.50 CAD");
      assertThat(cad(1050)).isNotEqualTo(1050L);
    }
  }

  @Nested
  class NoFloatingPoint {

    private static final Set<Class<?>> FORBIDDEN =
        Set.of(float.class, double.class, Float.class, Double.class, BigDecimal.class);

    @Test
    void fieldsDoNotUseFloatingPointOrBigDecimal() {
      for (Field field : Money.class.getDeclaredFields()) {
        assertThat(FORBIDDEN).as("field %s", field.getName()).doesNotContain(field.getType());
      }
    }

    @Test
    void constructorsDoNotAcceptFloatingPointOrBigDecimal() {
      for (Constructor<?> constructor : Money.class.getDeclaredConstructors()) {
        assertThat(Arrays.asList(constructor.getParameterTypes()))
            .as("constructor %s", constructor)
            .doesNotContainAnyElementsOf(FORBIDDEN);
      }
    }

    @Test
    void methodsDoNotAcceptOrReturnFloatingPointOrBigDecimal() {
      for (Method method : Money.class.getDeclaredMethods()) {
        assertThat(Arrays.asList(method.getParameterTypes()))
            .as("parameters of %s", method.getName())
            .doesNotContainAnyElementsOf(FORBIDDEN);
        assertThat(FORBIDDEN)
            .as("return type of %s", method.getName())
            .doesNotContain(method.getReturnType());
      }
    }
  }

  @Nested
  class Formatting {

    static Stream<Arguments> examples() {
      return Stream.of(
          Arguments.of(Money.of(1050, CAD), "10.50 CAD"),
          Arguments.of(Money.of(5, CAD), "0.05 CAD"),
          Arguments.of(Money.of(0, CAD), "0.00 CAD"),
          Arguments.of(Money.of(100_000_000, USD), "1000000.00 USD"),
          Arguments.of(Money.of(1050, JPY), "1050 JPY"),
          Arguments.of(Money.of(1050, BHD), "1.050 BHD"),
          Arguments.of(cad(0).subtractAllowingNegative(cad(800)), "-8.00 CAD"),
          Arguments.of(cad(0).subtractAllowingNegative(cad(5)), "-0.05 CAD"));
    }

    @ParameterizedTest
    @MethodSource("examples")
    void formatsWithCurrencyDecimalPlaces(Money money, String expected) {
      assertThat(money.toDisplayString()).isEqualTo(expected);
    }

    @Test
    void ignoresDefaultLocale() {
      Locale original = Locale.getDefault();
      try {
        Locale.setDefault(Locale.GERMANY);

        assertThat(Money.of(1050, CAD).toDisplayString()).isEqualTo("10.50 CAD");
      } finally {
        Locale.setDefault(original);
      }
    }
  }
}
