package com.adomako.payments.payment;

import static com.adomako.payments.payment.PaymentStatus.AUTHORIZED;
import static com.adomako.payments.payment.PaymentStatus.CAPTURED;
import static com.adomako.payments.payment.PaymentStatus.FAILED;
import static com.adomako.payments.payment.PaymentStatus.PARTIALLY_REFUNDED;
import static com.adomako.payments.payment.PaymentStatus.PENDING;
import static com.adomako.payments.payment.PaymentStatus.REFUNDED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

class PaymentStatusTest {

  record Transition(PaymentStatus from, PaymentStatus to) {
    @Override
    public String toString() {
      return from + " -> " + to;
    }
  }

  /** The expected state machine, written out independently of the implementation. */
  private static final List<Transition> ALLOWED =
      List.of(
          new Transition(PENDING, AUTHORIZED),
          new Transition(PENDING, FAILED),
          new Transition(AUTHORIZED, CAPTURED),
          new Transition(AUTHORIZED, FAILED),
          new Transition(CAPTURED, PARTIALLY_REFUNDED),
          new Transition(CAPTURED, REFUNDED),
          new Transition(PARTIALLY_REFUNDED, PARTIALLY_REFUNDED),
          new Transition(PARTIALLY_REFUNDED, REFUNDED));

  static Stream<Transition> allowedTransitions() {
    return ALLOWED.stream();
  }

  static Stream<Transition> disallowedTransitions() {
    return allPairs().filter(t -> !ALLOWED.contains(t));
  }

  private static Stream<Transition> allPairs() {
    return Arrays.stream(PaymentStatus.values())
        .flatMap(from -> Arrays.stream(PaymentStatus.values()).map(to -> new Transition(from, to)));
  }

  @ParameterizedTest(name = "[{index}] {0}")
  @MethodSource("allowedTransitions")
  void allowedTransitionIsAccepted(Transition t) {
    assertThat(t.from().canTransitionTo(t.to())).isTrue();
    assertThatCode(() -> t.from().requireTransitionTo(t.to())).doesNotThrowAnyException();
  }

  @ParameterizedTest(name = "[{index}] {0}")
  @MethodSource("disallowedTransitions")
  void disallowedTransitionIsRejected(Transition t) {
    assertThat(t.from().canTransitionTo(t.to())).isFalse();
    assertThatThrownBy(() -> t.from().requireTransitionTo(t.to()))
        .isInstanceOf(IllegalPaymentTransitionException.class)
        .hasMessageContaining(t.from().name())
        .hasMessageContaining(t.to().name());
  }

  @Test
  void illegalTransitionExceptionIsAnIllegalStateException() {
    assertThat(IllegalStateException.class)
        .isAssignableFrom(IllegalPaymentTransitionException.class);
  }

  @Test
  void matrixCoversEveryPairExactlyOnce() {
    List<Transition> allowed = allowedTransitions().toList();
    List<Transition> disallowed = disallowedTransitions().toList();

    assertThat(allowed).hasSize(8).doesNotHaveDuplicates();
    assertThat(disallowed).hasSize(28).doesNotContainAnyElementsOf(allowed);
    assertThat(Stream.concat(allowed.stream(), disallowed.stream()))
        .containsExactlyInAnyOrderElementsOf(allPairs().toList());
  }

  @Test
  void statusesAreExactlyTheExpectedSix() {
    assertThat(PaymentStatus.values())
        .containsExactly(PENDING, AUTHORIZED, CAPTURED, PARTIALLY_REFUNDED, REFUNDED, FAILED);
  }

  @ParameterizedTest
  @EnumSource(
      value = PaymentStatus.class,
      names = {"REFUNDED", "FAILED"})
  void terminalStatusesHaveNoExits(PaymentStatus terminal) {
    for (PaymentStatus next : PaymentStatus.values()) {
      assertThat(terminal.canTransitionTo(next)).as("%s -> %s", terminal, next).isFalse();
    }
  }

  @ParameterizedTest
  @EnumSource(PaymentStatus.class)
  void rejectsNullTarget(PaymentStatus from) {
    assertThatThrownBy(() -> from.canTransitionTo(null)).isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> from.requireTransitionTo(null))
        .isInstanceOf(NullPointerException.class);
  }
}
