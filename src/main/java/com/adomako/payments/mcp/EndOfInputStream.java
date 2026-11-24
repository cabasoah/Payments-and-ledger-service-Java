package com.adomako.payments.mcp;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.atomic.AtomicBoolean;

/** Runs a callback once when the wrapped stream reaches end of input (the client hung up). */
class EndOfInputStream extends FilterInputStream {

  private final Runnable onEnd;
  private final AtomicBoolean ended = new AtomicBoolean();

  EndOfInputStream(InputStream in, Runnable onEnd) {
    super(in);
    this.onEnd = onEnd;
  }

  @Override
  public int read() throws IOException {
    return checkEnd(super.read());
  }

  @Override
  public int read(byte[] buffer, int offset, int length) throws IOException {
    return checkEnd(super.read(buffer, offset, length));
  }

  private int checkEnd(int result) {
    if (result == -1 && ended.compareAndSet(false, true)) {
      onEnd.run();
    }
    return result;
  }
}
