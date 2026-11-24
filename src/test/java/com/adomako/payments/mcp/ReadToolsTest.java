package com.adomako.payments.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.adomako.payments.common.error.ApiException;
import com.adomako.payments.common.error.ErrorCode;
import com.adomako.payments.ledger.LedgerService;
import com.adomako.payments.ledger.PaymentLedger;
import com.adomako.payments.mcp.ToolOutputs.LedgerBalance;
import com.adomako.payments.mcp.ToolOutputs.PaymentList;
import com.adomako.payments.mcp.ToolOutputs.PaymentView;
import com.adomako.payments.payment.PaymentResponse;
import com.adomako.payments.payment.PaymentService;
import com.adomako.payments.payment.PaymentStatus;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class ReadToolsTest {

  private final PaymentService payments = mock(PaymentService.class);
  private final LedgerService ledger = mock(LedgerService.class);

  private static void assertReadOnly(Tool tool, String name, String... required) {
    assertThat(tool.name()).isEqualTo(name);
    assertThat(tool.description()).isNotBlank().doesNotStartWith(ToolDefinitions.WRITE_ACTION);
    assertThat(tool.annotations().readOnlyHint()).isTrue();
    assertThat(tool.annotations().destructiveHint()).isFalse();
    assertThat(tool.inputSchema()).containsEntry("type", "object");
    assertThat(tool.inputSchema().get("required")).isEqualTo(List.of(required));
  }

  private static void assertValidationFailed(Runnable call) {
    assertThatThrownBy(call::run)
        .isInstanceOfSatisfying(
            ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.VALIDATION_FAILED));
  }

  @Nested
  class GetPayment {

    private final GetPaymentTool tool = new GetPaymentTool(payments);

    @Test
    void definition() {
      assertReadOnly(tool.definition(), "get_payment", "paymentId");
      assertThat(tool.definition().description()).contains("refundable", "minor units");
    }

    @Test
    void returnsThePaymentWithEveryAmountInItsCurrency() {
      PaymentResponse payment = ToolOutputsTest.payment(PaymentStatus.CAPTURED, 1050, 1050, 0);
      when(payments.get(payment.id())).thenReturn(payment);

      Object result = tool.call(new ToolArguments(Map.of("paymentId", payment.id().toString())));

      assertThat(result).isEqualTo(PaymentView.from(payment));
    }

    @Test
    void badIdIsRejectedBeforeAnyLookup() {
      assertValidationFailed(() -> tool.call(new ToolArguments(Map.of("paymentId", "123"))));
      verifyNoInteractions(payments);
    }

    @Test
    void notFoundComesFromTheService() {
      UUID id = UUID.randomUUID();
      when(payments.get(id))
          .thenThrow(new ApiException(ErrorCode.PAYMENT_NOT_FOUND, "Payment does not exist."));

      assertThatThrownBy(() -> tool.call(new ToolArguments(Map.of("paymentId", id.toString()))))
          .isInstanceOfSatisfying(
              ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.PAYMENT_NOT_FOUND));
    }
  }

  @Nested
  class ListPayments {

    private final ListPaymentsTool tool = new ListPaymentsTool(payments);
    private final UUID merchant = UUID.randomUUID();

    @Test
    void definition() {
      assertReadOnly(tool.definition(), "list_payments", "merchantId");
      @SuppressWarnings("unchecked")
      Map<String, Object> limit =
          (Map<String, Object>)
              ((Map<String, Object>) tool.definition().inputSchema().get("properties"))
                  .get("limit");
      assertThat(limit)
          .containsEntry("type", "integer")
          .containsEntry("minimum", 1)
          .containsEntry("maximum", PaymentService.MAX_LIST_LIMIT)
          .containsEntry("default", ListPaymentsTool.DEFAULT_LIMIT);
    }

    @Test
    void limitDefaultsTo20() {
      when(payments.listRecent(merchant, 20)).thenReturn(List.of());

      tool.call(new ToolArguments(Map.of("merchantId", merchant.toString())));

      verify(payments).listRecent(merchant, 20);
    }

    @Test
    void passesTheGivenLimitToTheService() {
      // The service enforces 1..100, so the range check lives in one place.
      when(payments.listRecent(any(), anyInt())).thenReturn(List.of());

      tool.call(new ToolArguments(Map.of("merchantId", merchant.toString(), "limit", 5)));

      verify(payments).listRecent(merchant, 5);
    }

    @Test
    void returnsTheMerchantCountAndPayments() {
      PaymentResponse newest = ToolOutputsTest.payment(PaymentStatus.AUTHORIZED, 500, 0, 0);
      PaymentResponse older = ToolOutputsTest.payment(PaymentStatus.CAPTURED, 1050, 1050, 0);
      when(payments.listRecent(merchant, 20)).thenReturn(List.of(newest, older));

      Object result = tool.call(new ToolArguments(Map.of("merchantId", merchant.toString())));

      assertThat(result)
          .isEqualTo(
              new PaymentList(
                  merchant, 2, List.of(PaymentView.from(newest), PaymentView.from(older))));
    }

    @Test
    void nonIntegerLimitIsRejected() {
      assertValidationFailed(() ->
          tool.call(new ToolArguments(Map.of("merchantId", merchant.toString(), "limit", "ten"))));
      verifyNoInteractions(payments);
    }

    @Test
    void badMerchantIdIsRejected() {
      assertValidationFailed(() -> tool.call(new ToolArguments(Map.of("merchantId", "acme"))));
      verifyNoInteractions(payments);
    }
  }

  @Nested
  class GetLedgerBalance {

    private final GetLedgerBalanceTool tool = new GetLedgerBalanceTool(payments, ledger);

    @Test
    void definition() {
      assertReadOnly(tool.definition(), "get_ledger_balance", "paymentId");
      assertThat(tool.definition().description()).contains("balanced", "matchesPayment");
    }

    @Test
    void combinesThePaymentWithItsLedger() {
      PaymentResponse payment = ToolOutputsTest.payment(PaymentStatus.AUTHORIZED, 1050, 0, 0);
      PaymentLedger entries = new PaymentLedger(payment.id(), List.of());
      when(payments.get(payment.id())).thenReturn(payment);
      when(ledger.entriesForPayment(payment.id())).thenReturn(entries);

      Object result = tool.call(new ToolArguments(Map.of("paymentId", payment.id().toString())));

      assertThat(result).isEqualTo(LedgerBalance.from(payment, entries));
    }

    @Test
    void unknownPaymentIsNotFoundAndTheLedgerIsNotRead() {
      UUID id = UUID.randomUUID();
      when(payments.get(id))
          .thenThrow(new ApiException(ErrorCode.PAYMENT_NOT_FOUND, "Payment does not exist."));

      assertThatThrownBy(() -> tool.call(new ToolArguments(Map.of("paymentId", id.toString()))))
          .isInstanceOfSatisfying(
              ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.PAYMENT_NOT_FOUND));
      verifyNoInteractions(ledger);
    }
  }
}
