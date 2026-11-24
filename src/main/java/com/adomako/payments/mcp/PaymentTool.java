package com.adomako.payments.mcp;

import io.modelcontextprotocol.spec.McpSchema;

/**
 * One MCP tool: how it is described to the model, and what it does. {@link McpToolRunner} turns it
 * into an MCP tool handler that logs the call and converts exceptions into error results.
 */
public interface PaymentTool {

  McpSchema.Tool definition();

  /**
   * Runs the tool and returns the result object, which is sent to the model as JSON. Throws {@link
   * com.adomako.payments.common.error.ApiException} (or a payment domain exception) on failure.
   */
  Object call(ToolArguments arguments);
}
