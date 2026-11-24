package com.adomako.payments.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import com.adomako.payments.AbstractApiIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** With no {@code payments.mcp.read-only} setting, the server is read-only. */
class McpReadOnlyIT extends AbstractApiIT {

  @Autowired McpToolRegistry registry;

  @Test
  void onlyReadToolsAreOfferedByDefault() {
    assertThat(registry.specifications())
        .extracting(s -> s.tool().name())
        .containsExactly("get_payment", "list_payments", "get_ledger_balance");
  }

  @Test
  void everyOfferedToolIsMarkedReadOnly() {
    assertThat(registry.specifications())
        .allSatisfy(s -> {
          assertThat(s.tool().annotations().readOnlyHint()).isTrue();
          assertThat(s.tool().description()).doesNotStartWith(ToolDefinitions.WRITE_ACTION);
        });
  }
}
