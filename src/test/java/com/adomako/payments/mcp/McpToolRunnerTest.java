package com.adomako.payments.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.adomako.payments.common.error.ApiException;
import com.adomako.payments.common.error.ErrorCode;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

class McpToolRunnerTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private final McpToolRunner runner = new McpToolRunner(JSON);
  private final ListAppender<ILoggingEvent> audit = new ListAppender<>();
  private final Logger auditLogger = (Logger) LoggerFactory.getLogger(McpToolRunner.AUDIT_LOGGER);

  record Echo(String paymentId, long amount) {}

  /** A tool whose behaviour each test supplies. */
  private static PaymentTool tool(String name, Function<ToolArguments, Object> body) {
    return new PaymentTool() {
      @Override
      public Tool definition() {
        return ToolDefinitions.read(
            name, "Test tool", "For tests.", "{\"type\": \"object\", \"properties\": {}}");
      }

      @Override
      public Object call(ToolArguments arguments) {
        return body.apply(arguments);
      }
    };
  }

  private static Map<String, Object> textAsJson(CallToolResult result) {
    assertThat(result.content()).hasSize(1);
    String text = ((TextContent) result.content().getFirst()).text();
    return JSON.readValue(text, new TypeReference<Map<String, Object>>() {});
  }

  @BeforeEach
  void captureAuditLog() {
    audit.start();
    auditLogger.addAppender(audit);
  }

  @AfterEach
  void detachAuditLog() {
    auditLogger.detachAppender(audit);
  }

  @Test
  void successReturnsTheResultAsJson() {
    PaymentTool echo =
        tool("echo", a -> new Echo(a.requiredString("paymentId"), a.requiredLong("amount")));

    CallToolResult result = runner.run(echo, Map.of("paymentId", "p-1", "amount", 300));

    assertThat(result.isError()).isFalse();
    assertThat(textAsJson(result)).isEqualTo(Map.of("paymentId", "p-1", "amount", 300));
    assertThat(result.structuredContent()).isEqualTo(Map.of("paymentId", "p-1", "amount", 300));
  }

  @Test
  void apiErrorBecomesAnErrorResultWithCodeDetailAndHint() {
    PaymentTool failing =
        tool(
            "failing",
            a -> {
              throw new ApiException(
                  ErrorCode.REFUND_EXCEEDS_CAPTURED,
                  "Refund of 8.00 CAD exceeds the remaining refundable 7.50 CAD");
            });

    CallToolResult result = runner.run(failing, Map.of());

    assertThat(result.isError()).isTrue();
    Map<String, Object> body = textAsJson(result);
    assertThat(body).containsOnlyKeys("error");
    @SuppressWarnings("unchecked")
    Map<String, Object> error = (Map<String, Object>) body.get("error");
    assertThat(error)
        .containsEntry("code", "REFUND_EXCEEDS_CAPTURED")
        .containsEntry("title", ErrorCode.REFUND_EXCEEDS_CAPTURED.title())
        .containsEntry("detail", "Refund of 8.00 CAD exceeds the remaining refundable 7.50 CAD")
        .containsKey("hint");
    assertThat(result.structuredContent()).isEqualTo(body);
  }

  @Test
  void invalidArgumentsBecomeValidationFailed() {
    PaymentTool needsId = tool("needs_id", a -> a.requiredUuid("paymentId"));

    CallToolResult result = runner.run(needsId, Map.of("paymentId", "nope"));

    assertThat(result.isError()).isTrue();
    assertThat(textAsJson(result).get("error").toString()).contains("VALIDATION_FAILED");
  }

  @Test
  void unexpectedErrorIsInternalErrorWithoutItsMessage() {
    PaymentTool broken =
        tool(
            "broken",
            a -> {
              throw new IllegalStateException("secret internals");
            });

    CallToolResult result = runner.run(broken, Map.of());

    assertThat(result.isError()).isTrue();
    String text = ((TextContent) result.content().getFirst()).text();
    assertThat(text).contains("INTERNAL_ERROR").doesNotContain("secret internals");
  }

  @Test
  void nullArgumentsAreAllowed() {
    CallToolResult result = runner.run(tool("no_args", a -> Map.of("ok", true)), null);

    assertThat(result.isError()).isFalse();
  }

  @Test
  void successIsAuditedWithToolArgumentsAndResult() {
    PaymentTool echo =
        tool("echo", a -> new Echo(a.requiredString("paymentId"), a.requiredLong("amount")));

    runner.run(echo, Map.of("paymentId", "p-1", "amount", 300));

    assertThat(audit.list).hasSize(1);
    String line = audit.list.getFirst().getFormattedMessage();
    assertThat(line)
        .contains("tool=echo")
        .contains("outcome=ok")
        .contains("durationMs=")
        .contains("\"paymentId\":\"p-1\"")
        .contains("\"amount\":300");
  }

  @Test
  void failureIsAuditedWithItsErrorCode() {
    PaymentTool failing =
        tool(
            "refund_payment",
            a -> {
              throw new ApiException(ErrorCode.REFUND_EXCEEDS_CAPTURED, "too much");
            });

    runner.run(failing, Map.of("amount", 801));

    assertThat(audit.list).hasSize(1);
    String line = audit.list.getFirst().getFormattedMessage();
    assertThat(line)
        .contains("tool=refund_payment")
        .contains("outcome=REFUND_EXCEEDS_CAPTURED")
        .contains("\"amount\":801")
        .contains("too much");
  }

  @Test
  void specificationUsesTheToolDefinitionAndRunsTheTool() {
    PaymentTool echo = tool("echo", a -> new Echo(a.requiredString("paymentId"), 1));

    SyncToolSpecification spec = runner.specification(echo);
    CallToolResult result =
        spec.callHandler()
            .apply(null, new CallToolRequest("echo", Map.of("paymentId", "p-9"), null));

    assertThat(spec.tool().name()).isEqualTo("echo");
    assertThat(textAsJson(result)).containsEntry("paymentId", "p-9");
    assertThat(audit.list).hasSize(1);
  }
}
