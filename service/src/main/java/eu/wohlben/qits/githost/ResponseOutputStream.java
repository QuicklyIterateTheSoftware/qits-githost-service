package eu.wohlben.qits.githost;

import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpServerResponse;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.util.Arrays;

/**
 * A blocking {@link OutputStream} over a chunked Vert.x {@link HttpServerResponse}, for a worker
 * thread that produces a body as it goes — JGit's {@code UploadPack} writing a pack (qits-2172).
 *
 * <p>Every {@link #write} goes to the response at once, so the caller wraps it in {@link #buffered}
 * to batch: JGit writes in small pieces and its sideband progress {@code flush()}es, and a flush is
 * exactly what should reach the client promptly. The bytes are copied, because JGit reuses its
 * arrays.
 *
 * <p><b>Backpressure.</b> While {@link HttpServerResponse#writeQueueFull()} the writing thread waits
 * for the response's drain. It must never wait forever on a client that has gone: the wait wakes on
 * drain and re-checks {@link HttpServerResponse#closed()} on a short poll rather than taking the
 * response's single {@code closeHandler}/{@code exceptionHandler} slots, which vertx-web may hold.
 * A closed response makes every later write an {@link IOException}, so JGit aborts instead of
 * packing into the void.
 *
 * <p><b>Closing does not end the response.</b> Only {@link #end()} does, and only the caller that
 * saw the whole body produced calls it. A try-with-resources that closes this stream on an error
 * path therefore cannot make a half-written body look like a complete one — that is {@code
 * GitHostRoutes.fail}'s to break off.
 */
final class ResponseOutputStream extends OutputStream {

  /** The batching buffer {@link #buffered} puts in front of the response. */
  static final int BUFFER_SIZE = 64 * 1024;

  /** How often a writer waiting for drain re-checks that the client is still there. */
  private static final long CLOSED_POLL_MILLIS = 200;

  private final HttpServerResponse response;
  private final Object drained = new Object();

  ResponseOutputStream(HttpServerResponse response) {
    this.response = response;
    response.drainHandler(
        ignored -> {
          synchronized (drained) {
            drained.notifyAll();
          }
        });
  }

  /** This stream behind a {@link #BUFFER_SIZE} {@link BufferedOutputStream}. */
  static BufferedOutputStream buffered(ResponseOutputStream raw) {
    return new BufferedOutputStream(raw, BUFFER_SIZE);
  }

  @Override
  public void write(int b) throws IOException {
    write(new byte[] {(byte) b}, 0, 1);
  }

  @Override
  public void write(byte[] b, int off, int len) throws IOException {
    if (len == 0) {
      return;
    }
    awaitWritable();
    try {
      response.write(Buffer.buffer(Arrays.copyOfRange(b, off, off + len)));
    } catch (IllegalStateException e) {
      // Ended or closed between the check and the write.
      throw new IOException("the response is no longer writable", e);
    }
  }

  /** Every write is already handed to the response; there is nothing held here to push. */
  @Override
  public void flush() throws IOException {
    checkOpen();
  }

  /** Deliberately not {@link #end()} — see the class comment. */
  @Override
  public void close() {}

  /** Ends the response: the body is complete. Flush any buffer in front of this stream first. */
  void end() throws IOException {
    checkOpen();
    try {
      response.end();
    } catch (IllegalStateException e) {
      throw new IOException("the response is no longer writable", e);
    }
  }

  private void awaitWritable() throws IOException {
    synchronized (drained) {
      while (true) {
        checkOpen();
        if (!response.writeQueueFull()) {
          return;
        }
        try {
          drained.wait(CLOSED_POLL_MILLIS);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          throw new InterruptedIOException("interrupted waiting for the response to drain");
        }
      }
    }
  }

  private void checkOpen() throws IOException {
    if (response.closed()) {
      throw new IOException("the client closed the connection");
    }
    if (response.ended()) {
      throw new IOException("the response has already ended");
    }
  }
}
