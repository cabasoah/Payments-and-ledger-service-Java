package com.adomako.payments.mcp;

import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * The tools this app offers, as MCP tool specifications. Write tools are beans only when {@code
 * payments.mcp.read-only=false}, so in read-only mode they are never offered.
 */
@Component
public class McpToolRegistry {

  private final ObjectProvider<PaymentTool> tools;
  private final McpToolRunner runner;

  public McpToolRegistry(ObjectProvider<PaymentTool> tools, McpToolRunner runner) {
    this.tools = tools;
    this.runner = runner;
  }

  public List<SyncToolSpecification> specifications() {
    return tools.orderedStream().map(runner::specification).toList();
  }
}
