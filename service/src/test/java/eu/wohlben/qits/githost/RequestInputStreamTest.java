package eu.wohlben.qits.githost;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import io.vertx.core.Context;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerRequest;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@link RequestInputStream} against a real Vert.x server and a raw socket client (qits-2512) —
 * the request-side twin of {@link ResponseOutputStreamTest}. The socket is the point again: it is
 * the only honest way to send faster than the reader reads, or to hang up mid-body.
 *
 * <p>The server's request handler does what {@code GitHostRoutes.holdRequestBody} does — pause, and
 * hand the request and its context on — and the test thread plays the worker. The request it gets
 * is wrapped so every call the stream makes on it is recorded with the thread it was made on: a
 * Vert.x request belongs to its event loop, and a stream method called from the worker would be a
 * race nothing else here would show.
 *
 * <p>Nothing races a clock. Each case waits for a state it can observe before asserting the next.
 */
class RequestInputStreamTest {

  private static final int CAPACITY = 4;

  /** The request methods that must run on the request's context. */
  private static final Set<String> CONTEXT_BOUND =
      Set.of("pause", "resume", "fetch", "handler", "endHandler", "exceptionHandler");

  private Vertx vertx;
  private HttpServer server;
  private final LinkedBlockingQueue<Received> received = new LinkedBlockingQueue<>();
  private final List<String> offContextCalls = new CopyOnWriteArrayList<>();
  private final List<String> calls = new CopyOnWriteArrayList<>();

  private record Received(HttpServerRequest request, Context context) {}

  @BeforeEach
  void start() throws Exception {
    vertx = Vertx.vertx();
    server =
        vertx
            .createHttpServer()
            .requestHandler(
                req -> {
                  req.pause();
                  received.add(new Received(recording(req), Vertx.currentContext()));
                })
            .listen(0)
            .toCompletionStage()
            .toCompletableFuture()
            .get(10, TimeUnit.SECONDS);
  }

  @AfterEach
  void stop() throws Exception {
    vertx.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
  }

  @Test
  void aFastClientIsHeldToTheQueueAndEveryByteArrivesInOrder() throws Exception {
    byte[] payload = new byte[32 * 1024 * 1024];
    new Random(2512L).nextBytes(payload);

    try (Socket socket = connect()) {
      CompletableFuture<Void> sent = sendAsync(socket, head(payload.length), payload);
      Received request = received.poll(10, TimeUnit.SECONDS);
      RequestInputStream in = new RequestInputStream(request.request(), request.context(), CAPACITY);

      // Nobody reads yet. The stream must fill its queue, pause the request, and stop there — with
      // the client held back by TCP rather than by this process buffering 32 MiB.
      awaitTrue(() -> in.paused() && in.queuedBuffers() >= CAPACITY, "the request never paused");
      assertFalse(sent.isDone(), "a paused request must hold the client back");
      assertEquals(CAPACITY, in.queuedBuffers(), "nothing is queued past the capacity");

      ByteArrayOutputStream body = new ByteArrayOutputStream(payload.length);
      byte[] scratch = new byte[16 * 1024];
      int maxQueued = 0;
      int n;
      while ((n = in.read(scratch)) >= 0) {
        body.write(scratch, 0, n);
        maxQueued = Math.max(maxQueued, in.queuedBuffers());
      }
      assertArrayEquals(payload, body.toByteArray(), "every byte, in order, then EOF");
      assertTrue(maxQueued <= CAPACITY, "the queue never outgrew its capacity: " + maxQueued);
      assertTrue(in.ended(), "EOF is the request's end");
      assertEquals(-1, in.read(), "EOF stays EOF");
      sent.get(10, TimeUnit.SECONDS);
    }
    assertTrue(calls.contains("pause"), "the stream paused the request: " + calls);
    assertTrue(calls.contains("resume"), "the stream resumed the request: " + calls);
    assertEquals(List.of(), offContextCalls, "every request call ran on the request's context");
  }

  @Test
  void aClientThatHangsUpMidBodyFailsTheRead() throws Exception {
    byte[] part = new byte[64 * 1024];
    new Random(2513L).nextBytes(part);

    Socket socket = connect();
    // Promises a megabyte, sends 64 KiB of it, and goes.
    sendAsync(socket, head(1024 * 1024), part).get(10, TimeUnit.SECONDS);
    Received request = received.poll(10, TimeUnit.SECONDS);
    RequestInputStream in = new RequestInputStream(request.request(), request.context(), CAPACITY);
    awaitTrue(() -> in.queuedBuffers() > 0, "the first bytes never arrived");
    socket.setSoLinger(true, 0);
    socket.close();

    long read = 0;
    byte[] scratch = new byte[8192];
    try {
      int n;
      while ((n = in.read(scratch)) >= 0) {
        read += n;
      }
      fail("a body cut off mid-way must not read as a complete one; got EOF after " + read);
    } catch (IOException expected) {
      assertTrue(read <= part.length, "no more than was sent: " + read);
    }
    assertEquals(List.of(), offContextCalls, "every request call ran on the request's context");
  }

  @Test
  void closingWakesABlockedReader() throws Exception {
    try (Socket socket = connect()) {
      // The head only: the reader blocks waiting for a body that is not coming.
      sendAsync(socket, head(1024), new byte[0]).get(10, TimeUnit.SECONDS);
      Received request = received.poll(10, TimeUnit.SECONDS);
      RequestInputStream in = new RequestInputStream(request.request(), request.context(), CAPACITY);

      CompletableFuture<Throwable> outcome = new CompletableFuture<>();
      Thread reader =
          new Thread(
              () -> {
                try {
                  in.read();
                  outcome.complete(null);
                } catch (Throwable t) {
                  outcome.complete(t);
                }
              });
      reader.start();
      awaitTrue(() -> reader.getState() == Thread.State.WAITING, "the reader never blocked");
      in.close();
      assertInstanceOf(IOException.class, outcome.get(10, TimeUnit.SECONDS));
    }
  }

  @Test
  void anInterruptedReaderGivesUpWithInterruptedIOException() throws Exception {
    try (Socket socket = connect()) {
      sendAsync(socket, head(1024), new byte[0]).get(10, TimeUnit.SECONDS);
      Received request = received.poll(10, TimeUnit.SECONDS);
      RequestInputStream in = new RequestInputStream(request.request(), request.context(), CAPACITY);

      CompletableFuture<Throwable> outcome = new CompletableFuture<>();
      Thread reader =
          new Thread(
              () -> {
                try {
                  in.read();
                  outcome.complete(null);
                } catch (Throwable t) {
                  outcome.complete(t);
                }
              });
      reader.start();
      awaitTrue(() -> reader.getState() == Thread.State.WAITING, "the reader never blocked");
      reader.interrupt();
      assertInstanceOf(InterruptedIOException.class, outcome.get(10, TimeUnit.SECONDS));
    }
  }

  @Test
  void anEmptyBodyIsAnImmediateEof() throws Exception {
    try (Socket socket = connect()) {
      sendAsync(socket, head(0), new byte[0]).get(10, TimeUnit.SECONDS);
      Received request = received.poll(10, TimeUnit.SECONDS);
      RequestInputStream in = new RequestInputStream(request.request(), request.context(), CAPACITY);
      assertEquals(-1, in.read());
    }
  }

  // --- the client ---------------------------------------------------------------------------------

  private Socket connect() throws IOException {
    return new Socket("127.0.0.1", server.actualPort());
  }

  private static byte[] head(long contentLength) {
    return ("POST /git/x/git-receive-pack HTTP/1.1\r\n"
            + "Host: localhost\r\n"
            + "Content-Type: application/x-git-receive-pack-request\r\n"
            + "Content-Length: "
            + contentLength
            + "\r\n\r\n")
        .getBytes(StandardCharsets.US_ASCII);
  }

  /** Writes the head and then the body in 8 KiB pieces on a thread of its own. */
  private static CompletableFuture<Void> sendAsync(Socket socket, byte[] head, byte[] body) {
    return CompletableFuture.runAsync(
        () -> {
          try {
            OutputStream out = socket.getOutputStream();
            out.write(head);
            for (int off = 0; off < body.length; off += 8192) {
              out.write(body, off, Math.min(8192, body.length - off));
            }
            out.flush();
          } catch (IOException e) {
            throw new IllegalStateException(e);
          }
        },
        runnable -> {
          Thread thread = new Thread(runnable, "request-input-stream-test-client");
          thread.setDaemon(true);
          thread.start();
        });
  }

  // --- the recording request ----------------------------------------------------------------------

  /**
   * {@code real}, with every context-bound call recorded and checked for its thread. Fluent methods
   * hand back the wrapper, so a chained call is still seen.
   */
  private HttpServerRequest recording(HttpServerRequest real) {
    HttpServerRequest[] self = new HttpServerRequest[1];
    self[0] =
        (HttpServerRequest)
            Proxy.newProxyInstance(
                HttpServerRequest.class.getClassLoader(),
                new Class<?>[] {HttpServerRequest.class},
                (proxy, method, args) -> {
                  if (CONTEXT_BOUND.contains(method.getName())) {
                    calls.add(method.getName());
                    if (!Context.isOnEventLoopThread()) {
                      offContextCalls.add(method.getName() + " on " + Thread.currentThread());
                    }
                  }
                  try {
                    Object result = method.invoke(real, args);
                    return result == real ? self[0] : result;
                  } catch (InvocationTargetException e) {
                    throw e.getCause();
                  }
                });
    return self[0];
  }

  private static void awaitTrue(BooleanSupplier condition, String message)
      throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
    while (!condition.getAsBoolean()) {
      if (System.nanoTime() > deadline) {
        fail(message);
      }
      Thread.sleep(10);
    }
  }
}
