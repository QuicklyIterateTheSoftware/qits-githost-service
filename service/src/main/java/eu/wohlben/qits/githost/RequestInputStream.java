package eu.wohlben.qits.githost;

import io.vertx.core.Context;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpServerRequest;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.util.ArrayDeque;
import java.util.Objects;

/**
 * A blocking {@link InputStream} over a Vert.x {@link HttpServerRequest}'s body, for a worker thread
 * that consumes a body as it arrives — JGit's {@code ReceivePack} reading a pack (qits-2512). The
 * request-side twin of {@link ResponseOutputStream}.
 *
 * <p>It replaced a {@code BodyHandler} that held the whole pack in heap — twice, once as the body
 * and once as the {@code byte[]} JGit read from — and that therefore had to carry a limit: every
 * push past 64 MiB was a 413. Nothing under JGit needs the pack whole (the DFS pack store already
 * streams it to the blob store in chunks), so the body handler was the only reason the limit
 * existed. With this stream a push costs a constant handful of chunks of heap whatever its size.
 *
 * <p><b>The request must arrive paused.</b> Nothing here may miss a byte, and a request that is
 * flowing with no data handler drops what it reads. Quarkus pauses every request at its root
 * handler and nothing on these routes resumes it; {@code GitHostRoutes} pauses it again in the
 * first handler of each receive-pack chain anyway, so the guarantee is stated where it is relied on
 * rather than inherited.
 *
 * <p><b>Threads.</b> Constructed and read on the worker; every call that touches the request —
 * installing the handlers, {@code pause()}, {@code resume()} — is sent to the request's own {@code
 * context} with {@link Context#runOnContext}, because a Vert.x request is not thread-safe and its
 * stream methods belong to the event loop. The handlers run there too and hand buffers over through
 * a queue guarded by this object's monitor.
 *
 * <p><b>Backpressure.</b> At most {@code capacity} buffers are queued: the data handler pauses the
 * request when the queue reaches it, and the reader asks for a resume once it has drained the queue
 * to half. Vert.x stops delivering the moment a handler pauses, so the queue never meaningfully
 * overshoots, and a paused request stops reading from the socket — the client is held back by TCP,
 * not by heap. A reader waiting for data blocks on the monitor (no polling) and wakes on data, end,
 * failure or {@link #close()}; an interrupt is an {@link InterruptedIOException}.
 *
 * <p><b>Failure.</b> A request exception — the client hanging up mid-body is the common one, which
 * Vert.x reports to the request's exception handler as a closed connection — makes every later read
 * throw, <em>even if buffers are still queued</em>. A body the client abandoned is not one to act
 * on: JGit fails the receive, and since it updates refs only after the whole pack has parsed, a
 * push cut off mid-pack moves nothing.
 *
 * <p><b>Closing does not touch the connection.</b> It drops the queue, stops the reader and makes
 * the data handler discard; a request that was not read to its end stays paused. Whoever owns the
 * exchange decides what that means for the connection — see {@code GitHostRoutes}, which closes it
 * once the response is out rather than leave a keep-alive connection wedged behind an unread body.
 */
final class RequestInputStream extends InputStream {

  /**
   * How many buffers the stream holds before it pauses the request. A request buffer is one HTTP
   * chunk as Netty decoded it — at most {@code HttpServerOptions.maxChunkSize}, 8 KiB by default —
   * so this is a few hundred KiB at most, per push, whatever the pack weighs.
   */
  static final int DEFAULT_CAPACITY = 32;

  private final HttpServerRequest request;
  private final Context context;
  private final int capacity;
  private final int resumeAt;

  // Guarded by `this`.
  private final ArrayDeque<Buffer> queue = new ArrayDeque<>();
  private Buffer current;
  private int position;
  private boolean paused;
  private boolean resumeRequested;
  private boolean ended;
  private boolean closed;
  private Throwable failure;

  /**
   * Starts reading {@code request}'s body: the handlers are installed and the request resumed on
   * {@code context}, the event-loop context the request belongs to. Safe to call from any thread.
   */
  RequestInputStream(HttpServerRequest request, Context context, int capacity) {
    if (capacity < 2) {
      throw new IllegalArgumentException("capacity must be at least 2, was " + capacity);
    }
    this.request = Objects.requireNonNull(request, "request");
    this.context = Objects.requireNonNull(context, "context");
    this.capacity = capacity;
    this.resumeAt = capacity / 2;
    synchronized (this) {
      // Paused until install() resumes it: the request arrives paused, and the flag has to say so
      // before the first read can decide anything from it.
      paused = true;
    }
    context.runOnContext(ignored -> install());
  }

  /** On the request's context: the handlers, then the first resume. */
  private void install() {
    request.exceptionHandler(this::onFailure);
    request.endHandler(ignored -> onEnd());
    request.handler(this::onData);
    if (request.isEnded()) {
      // Already over (an empty body Vert.x saw end before anybody asked): there is nothing to fetch
      // and the end handler will not fire again.
      onEnd();
      return;
    }
    resumeIfStillWanted();
  }

  private void onData(Buffer buffer) {
    boolean pauseNow;
    synchronized (this) {
      if (closed) {
        return;
      }
      queue.add(buffer);
      pauseNow = !paused && queue.size() >= capacity;
      if (pauseNow) {
        paused = true;
      }
      notifyAll();
    }
    if (pauseNow) {
      // On the context already (this is the data handler); Vert.x delivers nothing more until the
      // reader's resume.
      request.pause();
    }
  }

  private synchronized void onEnd() {
    ended = true;
    notifyAll();
  }

  private synchronized void onFailure(Throwable t) {
    if (failure == null && !ended) {
      failure = t;
    }
    notifyAll();
  }

  /** On the context: resume, unless the stream was closed or refilled since the resume was asked. */
  private void resumeIfStillWanted() {
    synchronized (this) {
      resumeRequested = false;
      if (closed || ended || !paused || queue.size() > resumeAt) {
        return;
      }
      paused = false;
    }
    request.resume();
  }

  @Override
  public int read() throws IOException {
    byte[] one = new byte[1];
    int n = read(one, 0, 1);
    return n < 0 ? -1 : one[0] & 0xff;
  }

  @Override
  public int read(byte[] b, int off, int len) throws IOException {
    Objects.checkFromIndexSize(off, len, b.length);
    if (len == 0) {
      return 0;
    }
    synchronized (this) {
      if (!awaitData()) {
        return -1;
      }
      int n = Math.min(len, current.length() - position);
      current.getBytes(position, position + n, b, off);
      position += n;
      return n;
    }
  }

  /**
   * Blocks until {@link #current} has unread bytes; {@code false} at the end of the body. Caller
   * holds the monitor.
   */
  private boolean awaitData() throws IOException {
    while (current == null || position >= current.length()) {
      current = null;
      checkUsable();
      Buffer next = queue.poll();
      if (next != null) {
        current = next;
        position = 0;
        maybeAskForMore();
        continue;
      }
      if (ended) {
        return false;
      }
      try {
        wait();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new InterruptedIOException("interrupted waiting for the request body");
      }
    }
    return true;
  }

  /** Caller holds the monitor. Asks the context for a resume once the queue has half drained. */
  private void maybeAskForMore() {
    if (paused && !resumeRequested && !ended && queue.size() <= resumeAt) {
      resumeRequested = true;
      context.runOnContext(ignored -> resumeIfStillWanted());
    }
  }

  private void checkUsable() throws IOException {
    if (closed) {
      throw new IOException("the request body stream is closed");
    }
    if (failure != null) {
      throw new IOException("the request body failed: " + failure.getMessage(), failure);
    }
  }

  /** What can be read without blocking: the rest of the current buffer and every queued one. */
  @Override
  public synchronized int available() throws IOException {
    checkUsable();
    long total = current == null ? 0 : current.length() - position;
    for (Buffer queued : queue) {
      total += queued.length();
    }
    return (int) Math.min(Integer.MAX_VALUE, total);
  }

  /**
   * Drops what is queued and wakes a blocked reader; later reads throw. The request is left as it
   * is — see the class comment for why the connection is somebody else's.
   */
  @Override
  public synchronized void close() {
    if (closed) {
      return;
    }
    closed = true;
    queue.clear();
    current = null;
    notifyAll();
  }

  /** Whether the whole body has been received — the request's end has reached this stream. */
  synchronized boolean ended() {
    return ended;
  }

  /** Whether the stream currently holds the request paused. For tests. */
  synchronized boolean paused() {
    return paused;
  }

  /** How many buffers are waiting to be read. For tests. */
  synchronized int queuedBuffers() {
    return queue.size();
  }
}
