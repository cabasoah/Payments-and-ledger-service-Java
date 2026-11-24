package com.adomako.payments.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.adomako.payments.common.Money;
import com.adomako.payments.common.error.ApiException;
import com.adomako.payments.common.error.ErrorCode;
import com.adomako.payments.idempotency.IdempotentResponse;
import com.adomako.payments.mcp.ToolOutputs.PaymentResult;
import com.adomako.payments.mcp.ToolOutputs.PaymentView;
import com.adomako.payments.mcp.ToolOutputs.RefundResult;
import com.adomako.payments.mcp.ToolOutputs.RefundView;
import com.adomako.payments.payment.CreatePaymentRequest;
import com.adomako.payments.payment.PaymentCommands;
import com.adomako.payments.payment.PaymentResponse;
import com.adomako.payments.payment.PaymentService;
import com.adomako.payments.payment.PaymentStatus;
import com.adomako.payments.payment.RefundExceedsCapturedException;
import com.adomako.payments.payment.RefundRequest;
import com.adomako.payments.payment.RefundResponse;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.time.Instant;
import java.util.Currency;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class WriteToolsTest {

  private static final jakarta.validation.ValidatorFactory VALIDATION =
      Validation.buildDefaultValidatorFactory();
  private static final Validator VALIDATOR = VALIDATION.getValidator();
  private static final String KEY = "key-123";

  private final PaymentCommands commands = mock(PaymentCommands.class);
  private final PaymentService payments = mock(PaymentService.class);

  @AfterAll
  static void closeValidation() {
    VALIDATION.close();
  }

  private static void assertWriteTool(Tool tool, String name, String... required) {
    assertThat(tool.name()).isEqualTo(name);
    assertThat(tool.description()).startsWith("WRITE ACTION: moves money.");
    assertThat(tool.annotations().readOnlyHint()).isFalse();
    assertThat(tool.annotations().idempotentHint()).isTrue();
    assertThat(tool.inputSchema().get("required")).isEqualTo(List.of(required));
  }

  private static void assertRejected(ThrowingCallable call, ErrorCode code, String detailPart) {
    assertThatThrownBy(call)
        .isInstanceOfSatisfying(
            ApiException.class,
            e -> {
              assertThat(e.code()).isEqualTo(code);
              assertThat(e.getMessage()).contains(detailPart);
            });
  }

  @Nested
  class CreatePayment {

    private final CreatePaymentTool tool = new CreatePaymentTool(commands, VALIDATOR);
    private final UUID merchant = UUID.randomUUID();

    private Map<String, Object> validArgs() {
      Map<String, Object> args = new HashMap<>();
      args.put("merchantId", merchant.toString());
      args.put("amount", 1050);
      args.put("currency", "CAD");
      args.put("cardToken", "tok_visa_ok");
      args.put("idempotencyKey", KEY);
      return args;
    }

    private Map<String, Object> argsWith(String name, Object value) {
      Map<String, Object> args = validArgs();
      if (value == null) {
        args.remove(name);
      } else {
        args.put(name, value);
      }
      return args;
    }

    @Test
    void definition() {
      assertWriteTool(
          tool.definition(),
          "create_payment",
          "merchantId",
          "amount",
          "currency",
          "cardToken",
          "idempotencyKey");
      assertThat(tool.definition().annotations().destructiveHint()).isFalse();
      assertThat(tool.definition().description()).contains("idempotencyKey");
    }

    @Test
    void createsThroughTheIdempotentCommandWithTheKey() {
      PaymentResponse created = ToolOutputsTest.payment(PaymentStatus.AUTHORIZED, 1050, 0, 0);
      CreatePaymentRequest expected =
          new CreatePaymentRequest(merchant, 1050L, "CAD", "tok_visa_ok");
      when(commands.create(KEY, expected))
          .thenReturn(new IdempotentResponse<>(201, created, false));

      Object result = tool.call(new ToolArguments(validArgs()));

      assertThat(result).isEqualTo(new PaymentResult(PaymentView.from(created), false));
    }

    @Test
    void aReplayIsReportedAsReplayed() {
      PaymentResponse created = ToolOutputsTest.payment(PaymentStatus.AUTHORIZED, 1050, 0, 0);
      when(commands.create(any(), any())).thenReturn(new IdempotentResponse<>(201, created, true));

      PaymentResult result = (PaymentResult) tool.call(new ToolArguments(validArgs()));

      assertThat(result.replayed()).isTrue();
    }

    static List<Object[]> invalidArguments() {
      return List.of(
          new Object[] {"amount", 0, "amount"},
          new Object[] {"amount", -100, "amount"},
          new Object[] {"amount", 10.50, "whole number of minor units"},
          new Object[] {"amount", null, "amount is required"},
          new Object[] {"currency", "cad", "currency"},
          new Object[] {"currency", "CADX", "currency"},
          new Object[] {"cardToken", "", "cardToken is required"},
          new Object[] {"merchantId", "acme", "merchantId must be a UUID"},
          new Object[] {"merchantId", null, "merchantId is required"});
    }

    @ParameterizedTest(name = "[{index}] {0} = {1}")
    @MethodSource("invalidArguments")
    void invalidArgumentsAreRejectedBeforeAnythingRuns(String name, Object value, String detail) {
      assertRejected(
          () -> tool.call(new ToolArguments(argsWith(name, value))),
          ErrorCode.VALIDATION_FAILED,
          detail);
      verifyNoInteractions(commands);
    }

    @Test
    void missingKeyIsRejectedBeforeAnythingRuns() {
      assertRejected(
          () -> tool.call(new ToolArguments(argsWith("idempotencyKey", null))),
          ErrorCode.IDEMPOTENCY_KEY_MISSING,
          "idempotencyKey");
      verifyNoInteractions(commands);
    }

    @Test
    void unsupportedCurrencyErrorComesFromTheService() {
      // "ABC" passes the format check; PaymentService rejects it, as it does for REST.
      when(commands.create(any(), any()))
          .thenThrow(new ApiException(ErrorCode.VALIDATION_FAILED, "Unsupported currency: ABC."));

      assertRejected(
          () -> tool.call(new ToolArguments(argsWith("currency", "ABC"))),
          ErrorCode.VALIDATION_FAILED,
          "Unsupported currency");
    }
  }

  @Nested
  class CapturePayment {

    private final CapturePaymentTool tool = new CapturePaymentTool(commands);

    @Test
    void definition() {
      assertWriteTool(tool.definition(), "capture_payment", "paymentId", "idempotencyKey");
      assertThat(tool.definition().annotations().destructiveHint()).isTrue();
    }

    @Test
    void capturesThroughTheIdempotentCommandWithTheKey() {
      PaymentResponse captured = ToolOutputsTest.payment(PaymentStatus.CAPTURED, 1050, 1050, 0);
      when(commands.capture(KEY, captured.id()))
          .thenReturn(new IdempotentResponse<>(200, captured, false));

      Object result =
          tool.call(
              new ToolArguments(
                  Map.of("paymentId", captured.id().toString(), "idempotencyKey", KEY)));

      assertThat(result).isEqualTo(new PaymentResult(PaymentView.from(captured), false));
    }

    @Test
    void missingKeyIsRejected() {
      assertRejected(
          () -> tool.call(new ToolArguments(Map.of("paymentId", UUID.randomUUID().toString()))),
          ErrorCode.IDEMPOTENCY_KEY_MISSING,
          "idempotencyKey");
      verifyNoInteractions(commands);
    }

    @Test
    void badIdIsRejected() {
      assertRejected(
          () -> tool.call(new ToolArguments(Map.of("paymentId", "p1", "idempotencyKey", KEY))),
          ErrorCode.VALIDATION_FAILED,
          "paymentId must be a UUID");
      verifyNoInteractions(commands);
    }
  }

  @Nested
  class RefundPayment {

    private final RefundPaymentTool tool = new RefundPaymentTool(commands, payments, VALIDATOR);
    private final UUID paymentId = UUID.randomUUID();

    private Map<String, Object> args(Object amount) {
      Map<String, Object> args = new HashMap<>();
      args.put("paymentId", paymentId.toString());
      args.put("amount", amount);
      args.put("idempotencyKey", KEY);
      return args;
    }

    @Test
    void definition() {
      assertWriteTool(tool.definition(), "refund_payment", "paymentId", "amount", "idempotencyKey");
      assertThat(tool.definition().annotations().destructiveHint()).isTrue();
      assertThat(tool.definition().description()).contains("REFUND_EXCEEDS_CAPTURED");
    }

    @Test
    void refundsThroughTheIdempotentCommandAndReturnsThePaymentAfter() {
      RefundResponse refund =
          new RefundResponse(UUID.randomUUID(), paymentId, 300, "CAD", Instant.now());
      PaymentResponse after =
          ToolOutputsTest.payment(PaymentStatus.PARTIALLY_REFUNDED, 1050, 1050, 300, refund);
      when(commands.refund(KEY, paymentId, new RefundRequest(300L)))
          .thenReturn(new IdempotentResponse<>(201, refund, false));
      when(payments.get(paymentId)).thenReturn(after);

      Object result = tool.call(new ToolArguments(args(300)));

      assertThat(result)
          .isEqualTo(new RefundResult(RefundView.from(refund), PaymentView.from(after), false));
    }

    @Test
    void overRefundErrorPropagatesAndThePaymentIsNotReRead() {
      Currency cad = Currency.getInstance("CAD");
      when(commands.refund(any(), any(), any()))
          .thenThrow(new RefundExceedsCapturedException(Money.of(1100, cad), Money.of(1050, cad)));

      assertThatThrownBy(() -> tool.call(new ToolArguments(args(1100))))
          .isInstanceOf(RefundExceedsCapturedException.class)
          .hasMessage("Refund of 11.00 CAD exceeds the remaining refundable 10.50 CAD");
      verify(payments, never()).get(any());
    }

    @Test
    void nonPositiveAmountIsRejected() {
      assertRejected(
          () -> tool.call(new ToolArguments(args(0))), ErrorCode.VALIDATION_FAILED, "amount");
      verifyNoInteractions(commands);
    }

    @Test
    void decimalAmountIsRejected() {
      assertRejected(
          () -> tool.call(new ToolArguments(args(3.00))),
          ErrorCode.VALIDATION_FAILED,
          "whole number of minor units");
      verifyNoInteractions(commands);
    }

    @Test
    void missingKeyIsRejected() {
      Map<String, Object> args = args(300);
      args.remove("idempotencyKey");

      assertRejected(
          () -> tool.call(new ToolArguments(args)),
          ErrorCode.IDEMPOTENCY_KEY_MISSING,
          "idempotencyKey");
      verifyNoInteractions(commands);
    }
  }
}
