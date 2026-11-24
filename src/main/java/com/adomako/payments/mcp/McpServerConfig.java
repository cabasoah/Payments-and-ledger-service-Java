package com.adomako.payments.mcp;

import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema.ServerCapabilities;
import io.modelcontextprotocol.spec.McpServerTransportProvider;
import java.io.InputStream;
import java.util.List;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Runs an MCP server over stdio when {@code payments.mcp.enabled=true} (the {@code mcp} profile).
 * The AI client starts this app as a subprocess and talks JSON-RPC over stdin/stdout, so nothing
 * else may write to stdout: the {@code mcp} profile sends logs to stderr.
 */
@Configuration
@ConditionalOnProperty(name = "payments.mcp.enabled", havingValue = "true")
public class McpServerConfig {

  static final String INSTRUCTIONS =
      "Tools for a merchant payments service with a double-entry ledger. Amounts are integers in"
          + " minor units (cents) and always come with an ISO 4217 currency code: amount 1050 with"
          + " currency CAD is 10.50 CAD. Tools whose description starts with WRITE ACTION move"
          + " money; confirm with the user before calling them.";

  /** stdin/stdout transport. When the client closes stdin, the app shuts down. */
  @Bean
  McpServerTransportProvider mcpTransport(ConfigurableApplicationContext context) {
    InputStream in = new EndOfInputStream(System.in, () -> shutDown(context));
    return new StdioServerTransportProvider(McpJsonDefaults.getMapper(), in, System.out);
  }

  @Bean(destroyMethod = "closeGracefully")
  McpSyncServer mcpServer(McpServerTransportProvider transport, McpToolRegistry registry) {
    return build(transport, registry.specifications());
  }

  /**
   * The SDK's own JSON-schema check of tool inputs is off: it would reject a call before it reaches
   * {@link McpToolRunner}, so the call would not be audited and its error would not have our
   * format. The tools validate their inputs themselves.
   */
  static McpSyncServer build(
      McpServerTransportProvider transport, List<SyncToolSpecification> tools) {
    return McpServer.sync(transport)
        .serverInfo("payments-ledger", "0.0.1")
        .instructions(INSTRUCTIONS)
        .capabilities(ServerCapabilities.builder().tools(false).build())
        .validateToolInputs(false)
        .tools(tools)
        .build();
  }

  private static void shutDown(ConfigurableApplicationContext context) {
    new Thread(() -> System.exit(SpringApplication.exit(context)), "mcp-shutdown").start();
  }
}
