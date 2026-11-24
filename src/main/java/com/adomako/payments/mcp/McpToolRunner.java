package com.adomako.payments.mcp;

import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import java.util.Map;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Runs a {@link PaymentTool} for the MCP server. Every call is written to the {@code mcp.audit} log
 * with its arguments, outcome and result, so a reviewer can trace what an agent did. Success
 * returns the result as JSON; failure returns {@code isError: true} with a {@link ToolError}.
 */
@Component
public class McpToolRunner {

  static final String AUDIT_LOGGER = "mcp.audit";

  private final JsonMapper jsonMapper;

  public McpToolRunner(JsonMapper jsonMapper) {
    this.jsonMapper = jsonMapper;
  }

  public SyncToolSpecification specification(PaymentTool tool) {
    throw new UnsupportedOperationException("not implemented yet");
  }

  public CallToolResult run(PaymentTool tool, Map<String, Object> arguments) {
    throw new UnsupportedOperationException("not implemented yet");
  }
}
