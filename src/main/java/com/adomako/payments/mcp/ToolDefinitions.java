package com.adomako.payments.mcp;

import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import io.modelcontextprotocol.spec.McpSchema.ToolAnnotations;

/** Builds tool definitions. Input schemas are JSON Schema text, as the model will read them. */
final class ToolDefinitions {

  /** Every write tool's description starts with this, so the model and the user can't miss it. */
  static final String WRITE_ACTION = "WRITE ACTION: moves money. ";

  static final String PAYMENT_ID =
      """
      "paymentId": {"type": "string", "format": "uuid", "description": "The payment's id (a UUID)."}\
      """;

  static final String IDEMPOTENCY_KEY =
      """
      "idempotencyKey": {"type": "string", "minLength": 1, "maxLength": 255,
        "description": "A new unique value (e.g. a UUID) for each new operation. If a call fails or \
      times out, retry with the SAME key and the same arguments: the operation happens at most once. \
      Never reuse a key for a different request."}\
      """;

  /** Name of the property that enables the write tools when set to {@code false}. */
  static final String READ_ONLY_PROPERTY = "payments.mcp.read-only";

  private ToolDefinitions() {}

  static Tool read(String name, String title, String description, String inputSchema) {
    return tool(
        name,
        title,
        description,
        inputSchema,
        ToolAnnotations.builder()
            .title(title)
            .readOnlyHint(true)
            .destructiveHint(false)
            .idempotentHint(true)
            .openWorldHint(false)
            .build());
  }

  /**
   * @param destructive true when the tool changes an existing payment (capture, refund) rather than
   *     only adding a new one
   */
  static Tool write(
      String name, String title, String description, String inputSchema, boolean destructive) {
    return tool(
        name,
        title,
        WRITE_ACTION + description,
        inputSchema,
        ToolAnnotations.builder()
            .title(title)
            .readOnlyHint(false)
            .destructiveHint(destructive)
            .idempotentHint(true)
            .openWorldHint(false)
            .build());
  }

  private static Tool tool(
      String name,
      String title,
      String description,
      String inputSchema,
      ToolAnnotations annotations) {
    return Tool.builder()
        .name(name)
        .title(title)
        .description(description)
        .inputSchema(McpJsonDefaults.getMapper(), inputSchema)
        .annotations(annotations)
        .build();
  }
}
