package com.adomako.payments.mcp;

import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Runs a {@link PaymentTool} for the MCP server. Every call is written to the {@code mcp.audit} log
 * with its arguments, outcome and result, so a reviewer can trace what an agent did. Success
 * returns the result as JSON; failure returns {@code isError: true} with a {@link ToolError}.
 */
@Component
public class McpToolRunner {

  static final String AUDIT_LOGGER = "mcp.audit";

  private static final Logger audit = LoggerFactory.getLogger(AUDIT_LOGGER);
  private static final Logger log = LoggerFactory.getLogger(McpToolRunner.class);

  private final JsonMapper jsonMapper;

  public McpToolRunner(JsonMapper jsonMapper) {
    this.jsonMapper = jsonMapper;
  }

  public SyncToolSpecification specification(PaymentTool tool) {
    return SyncToolSpecification.builder()
        .tool(tool.definition())
        .callHandler((exchange, request) -> run(tool, request.arguments()))
        .build();
  }

  public CallToolResult run(PaymentTool tool, Map<String, Object> arguments) {
    String name = tool.definition().name();
    ToolArguments args = new ToolArguments(arguments);
    long start = System.nanoTime();

    Object body;
    String outcome;
    boolean isError;
    try {
      body = tool.call(args);
      outcome = "ok";
      isError = false;
    } catch (RuntimeException e) {
      ToolError error = ToolError.from(e);
      if ("INTERNAL_ERROR".equals(error.code())) {
        log.error("Tool {} failed unexpectedly", name, e);
      }
      body = Map.of("error", error);
      outcome = error.code();
      isError = true;
    }

    String json = jsonMapper.writeValueAsString(body);
    long durationMs = (System.nanoTime() - start) / 1_000_000;
    audit.info(
        "tool={} outcome={} durationMs={} arguments={} result={}",
        name,
        outcome,
        durationMs,
        jsonMapper.writeValueAsString(args.raw()),
        json);

    return CallToolResult.builder()
        .addTextContent(json)
        .structuredContent(jsonMapper.readValue(json, new TypeReference<Map<String, Object>>() {}))
        .isError(isError)
        .build();
  }
}
