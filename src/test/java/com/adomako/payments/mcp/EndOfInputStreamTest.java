package com.adomako.payments.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class EndOfInputStreamTest {

  @Test
  void runsTheCallbackOnceAtEndOfInput() throws IOException {
    AtomicInteger calls = new AtomicInteger();
    byte[] data = "hello\n".getBytes(StandardCharsets.UTF_8);
    try (var in = new EndOfInputStream(new ByteArrayInputStream(data), calls::incrementAndGet)) {
      assertThat(in.readAllBytes()).isEqualTo(data);
      assertThat(in.read()).isEqualTo(-1);
      assertThat(in.read(new byte[4], 0, 4)).isEqualTo(-1);
    }
    assertThat(calls).hasValue(1);
  }

  @Test
  void doesNotRunTheCallbackWhileDataRemains() throws IOException {
    AtomicInteger calls = new AtomicInteger();
    try (var in =
        new EndOfInputStream(new ByteArrayInputStream(new byte[] {1, 2}), calls::incrementAndGet)) {
      assertThat(in.read()).isEqualTo(1);
    }
    assertThat(calls).hasValue(0);
  }
}
