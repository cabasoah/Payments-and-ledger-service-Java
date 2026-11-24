package com.adomako.payments.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import com.adomako.payments.AbstractApiIT;
import com.adomako.payments.payment.PaymentResponse;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Speaks real MCP JSON-RPC to the server, built exactly as the {@code mcp} profile builds it, over
 * in-memory pipes instead of stdin/stdout. Read-only (the default).
 */
class McpStdioIT extends AbstractApiIT {

  @Autowired McpToolRegistry registry;
  @Autowired JsonMapper json;

  private McpSyncServer server;
  private OutputStream toServer;
  private BufferedReader fromServer;
  private int nextId = 1;

  @BeforeEach
  void startServer() throws IOException {
    PipedOutputStream clientOut = new PipedOutputStream();
    PipedInputStream serverIn = new PipedInputStream(clientOut, 1 << 16);
    PipedOutputStream serverOut = new PipedOutputStream();
    PipedInputStream clientIn = new PipedInputStream(serverOut, 1 << 16);
    toServer = clientOut;
    fromServer = new BufferedReader(new InputStreamReader(clientIn, StandardCharsets.UTF_8));

    server =
        McpServerConfig.build(
            new StdioServerTransportProvider(McpJsonDefaults.getMapper(), serverIn, serverOut),
            registry.specifications());

    Map<String, Object> init =
        request(
            "initialize",
            Map.of(
                "protocolVersion", "2025-06-18",
                "capabilities", Map.of(),
                "clientInfo", Map.of("name", "test", "version", "1")));
    assertThat(result(init)).containsKey("serverInfo");
    send(Map.of("jsonrpc", "2.0", "method", "notifications/initialized"));
  }

  @AfterEach
  void stopServer() throws IOException {
    server.closeGracefully();
    toServer.close();
  }

  private void send(Map<String, Object> message) throws IOException {
    toServer.write((json.writeValueAsString(message) + "\n").getBytes(StandardCharsets.UTF_8));
    toServer.flush();
  }

  /** Sends a request and waits for the response with the same id. */
  private Map<String, Object> request(String method, Map<String, Object> params)
      throws IOException {
    int id = nextId++;
    send(Map.of("jsonrpc", "2.0", "id", id, "method", method, "params", params));
    while (true) {
      String line = readLine();
      Map<String, Object> message = json.readValue(line, new TypeReference<>() {});
      if (Integer.valueOf(id).equals(message.get("id"))) {
        return message;
      }
    }
  }

  private String readLine() {
    try {
      return CompletableFuture.supplyAsync(
              () -> {
                try {
                  return fromServer.readLine();
                } catch (IOException e) {
                  throw new IllegalStateException(e);
                }
              })
          .get(10, TimeUnit.SECONDS);
    } catch (Exception e) {
      throw new AssertionError("no response from the MCP server", e);
    }
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> result(Map<String, Object> response) {
    assertThat(response).as("JSON-RPC response: %s", response).containsKey("result");
    return (Map<String, Object>) response.get("result");
  }

  @SuppressWarnings("unchecked")
  private static String text(Map<String, Object> callResult) {
    List<Map<String, Object>> content = (List<Map<String, Object>>) callResult.get("content");
    return (String) content.getFirst().get("text");
  }

  @Test
  void initializeAnnouncesToolsAndInstructions() throws IOException {
    // initialize already ran in @BeforeEach; tools/list proves the session is usable.
    Map<String, Object> list = result(request("tools/list", Map.of()));

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> tools = (List<Map<String, Object>>) list.get("tools");
    assertThat(tools)
        .extracting(t -> t.get("name"))
        .containsExactly("get_payment", "list_payments", "get_ledger_balance");
    assertThat(tools.getFirst()).containsKeys("description", "inputSchema", "annotations");
  }

  @Test
  void callsAToolOverTheProtocol() throws IOException {
    PaymentResponse payment = capturedPayment(1050);

    Map<String, Object> call =
        result(
            request(
                "tools/call",
                Map.of(
                    "name",
                    "get_payment",
                    "arguments",
                    Map.of("paymentId", payment.id().toString()))));

    assertThat(call.get("isError")).isEqualTo(false);
    assertThat(text(call)).contains(payment.id().toString()).contains("\"display\":\"10.50 CAD\"");
  }

  @Test
  void badArgumentsReachOurValidationNotTheSdks() throws IOException {
    // A number where the schema says string. The SDK's own schema check would answer with its
    // own message and skip the audit log; our validation answers with VALIDATION_FAILED.
    Map<String, Object> call =
        result(
            request(
                "tools/call",
                Map.of("name", "get_payment", "arguments", Map.of("paymentId", 123))));

    assertThat(call.get("isError")).isEqualTo(true);
    assertThat(text(call)).contains("VALIDATION_FAILED").contains("paymentId must be a UUID");
  }
}
