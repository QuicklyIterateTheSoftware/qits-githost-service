package eu.wohlben.qits.githost;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerResponse;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@link ResponseOutputStream} and {@link GitHostRoutes#failResponse} against a real Vert.x server
 * and a raw socket client (qits-2172). The socket is the point: it shows the chunked framing exactly
 * as a git client sees it, and it lets a test stop reading — the only honest way to fill the write
 * queue — or hang up mid-body.
 *
 * <p>Nothing here races a clock. Each case waits for a state it can observe (bytes on the socket, a
 * writer parked in {@code awaitWritable}, a finished writer) before asserting the next.
 */
class ResponseOutputStreamTest {

  private Vertx vertx;
  private HttpServer server;
  private ExecutorService worker;

  /** What the server does with the response, on a worker thread — the {@code blockingHandler}. */
  private volatile WorkerBody body;

  /** The writer's outcome: {@code null} when it returned normally, else what it threw. */
  private CompletableFuture<Throwable> outcome;

  private Thread writerThread;

  @FunctionalInterface
  interface WorkerBody {
    void run(HttpServerResponse response) throws Exception;
  }

  @BeforeEach
  void start() throws Exception {
    vertx = Vertx.vertx();
    worker = Executors.newSingleThreadExecutor();
    outcome = new CompletableFuture<>();
    server =
        vertx
            .createHttpServer()
            .requestHandler(
                req ->
                    worker.submit(
                        () -> {
                          writerThread = Thread.currentThread();
                          try {
                            body.run(req.response());
                            outcome.complete(null);
                          } catch (Throwable t) {
                            outcome.complete(t);
                          }
                        }))
            .listen(0)
            .toCompletionStage()
            .toCompletableFuture()
            .get(10, TimeUnit.SECONDS);
  }

  @AfterEach
  void stop() throws Exception {
    worker.shutdownNow();
    vertx.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
  }

  @Test
  void aFlushReachesTheClientBeforeTheResponseEnds() throws Exception {
    CountDownLatch clientSawTheChunk = new CountDownLatch(1);
    body =
        response -> {
          response.setStatusCode(200).setChunked(true);
          ResponseOutputStream raw = new ResponseOutputStream(response);
          OutputStream out = ResponseOutputStream.buffered(raw);
          out.write("first".getBytes(StandardCharsets.US_ASCII));
          out.flush();
          // The writer does not go on — and so cannot end — until the client has the bytes.
          if (!clientSawTheChunk.await(30, TimeUnit.SECONDS)) {
            throw new IllegalStateException("the flushed bytes never reached the client");
          }
          out.write("second".getBytes(StandardCharsets.US_ASCII));
          out.flush();
          raw.end();
        };

    try (Socket socket = connect()) {
      InputStream in = socket.getInputStream();
      Map<String, String> headers = readHead(in);
      assertEquals("chunked", headers.get("transfer-encoding"));
      assertArrayEquals("first".getBytes(StandardCharsets.US_ASCII), readChunk(in));
      assertFalse(outcome.isDone(), "the writer must still be running: nothing has ended yet");
      clientSawTheChunk.countDown();
      assertArrayEquals("second".getBytes(StandardCharsets.US_ASCII), readChunk(in));
      assertArrayEquals(new byte[0], readChunk(in), "a completed body ends with the 0-chunk");
    }
    assertNull(outcome.get(10, TimeUnit.SECONDS));
  }

  @Test
  void aWriterBlocksWhileTheQueueIsFullAndResumesOnDrain() throws Exception {
    byte[] payload = new byte[32 * 1024 * 1024];
    new Random(2172L).nextBytes(payload);
    body =
        response -> {
          response.setStatusCode(200).setChunked(true).setWriteQueueMaxSize(64 * 1024);
          ResponseOutputStream raw = new ResponseOutputStream(response);
          OutputStream out = ResponseOutputStream.buffered(raw);
          for (int off = 0; off < payload.length; off += 8192) {
            out.write(payload, off, 8192);
          }
          out.flush();
          raw.end();
        };

    try (Socket socket = connect()) {
      InputStream in = socket.getInputStream();
      // Nobody reads: once the socket buffers fill, the writer must park waiting for drain.
      awaitTrue(this::writerIsParked, "the writer never blocked on a full write queue");
      assertFalse(outcome.isDone(), "a parked writer has not finished");

      Map<String, String> headers = readHead(in);
      assertEquals("chunked", headers.get("transfer-encoding"));
      byte[] received = readChunkedBody(in);
      assertArrayEquals(payload, received, "every byte arrives, in order, once the client reads");
    }
    assertNull(outcome.get(30, TimeUnit.SECONDS), "the writer resumed and finished after drain");
  }

  @Test
  void aClientThatHangsUpWakesTheBlockedWriterWithAnIOException() throws Exception {
    byte[] block = new byte[8192];
    new Random(2173L).nextBytes(block);
    body =
        response -> {
          response.setStatusCode(200).setChunked(true).setWriteQueueMaxSize(64 * 1024);
          ResponseOutputStream raw = new ResponseOutputStream(response);
          OutputStream out = ResponseOutputStream.buffered(raw);
          while (true) {
            out.write(block); // never ends on its own: only a failure stops it
          }
        };

    Socket socket = connect();
    awaitTrue(this::writerIsParked, "the writer never blocked on a full write queue");
    socket.setSoLinger(true, 0);
    socket.close();

    Throwable thrown = outcome.get(30, TimeUnit.SECONDS);
    assertInstanceOf(IOException.class, thrown, "the waiting writer must give up, not hang");
  }

  @Test
  void aFailureAfterBytesWereStreamedBreaksTheConnectionInsteadOfEndingIt() throws Exception {
    body =
        response -> {
          response.setStatusCode(200).setChunked(true);
          ResponseOutputStream raw = new ResponseOutputStream(response);
          OutputStream out = ResponseOutputStream.buffered(raw);
          out.write("PACK-part".getBytes(StandardCharsets.US_ASCII));
          out.flush();
          out.close(); // what a try-with-resources on the error path would do
          GitHostRoutes.failResponse(response);
        };

    try (Socket socket = connect()) {
      InputStream in = socket.getInputStream();
      Map<String, String> headers = readHead(in);
      assertTrue(headers.get(":status").contains(" 200 "), headers.get(":status"));
      assertArrayEquals("PACK-part".getBytes(StandardCharsets.US_ASCII), readChunk(in));
      try {
        byte[] next = readChunk(in);
        fail("the body must not complete; got another chunk of " + next.length + " bytes");
      } catch (EOFException | java.net.SocketException expected) {
        // The connection was broken off mid-body, never terminated with the 0-chunk.
      }
    }
    assertNull(outcome.get(10, TimeUnit.SECONDS));
  }

  @Test
  void aFailureBeforeAnyHeadIsStillA500() throws Exception {
    body =
        response -> {
          response.setStatusCode(200).setChunked(true);
          ResponseOutputStream raw = new ResponseOutputStream(response);
          OutputStream out = ResponseOutputStream.buffered(raw);
          out.write("never flushed".getBytes(StandardCharsets.US_ASCII));
          GitHostRoutes.failResponse(response);
        };

    try (Socket socket = connect()) {
      InputStream in = socket.getInputStream();
      Map<String, String> headers = readHead(in);
      assertTrue(headers.get(":status").contains(" 500 "), headers.get(":status"));
    }
    assertNull(outcome.get(10, TimeUnit.SECONDS));
  }

  // ---- helpers ------------------------------------------------------------------------------

  private Socket connect() throws IOException {
    Socket socket = new Socket();
    // Small, and set before connecting so the window is negotiated with it: a client that stops
    // reading then fills the server's write queue after a few hundred KiB rather than many MiB.
    socket.setReceiveBufferSize(64 * 1024);
    socket.connect(new java.net.InetSocketAddress("127.0.0.1", server.actualPort()), 10_000);
    socket.setSoTimeout(30_000);
    socket
        .getOutputStream()
        .write(
            "POST /git-upload-pack HTTP/1.1\r\nHost: localhost\r\nContent-Length: 0\r\n\r\n"
                .getBytes(StandardCharsets.US_ASCII));
    socket.getOutputStream().flush();
    return socket;
  }

  /** The writer thread is parked inside {@code ResponseOutputStream.awaitWritable}. */
  private boolean writerIsParked() {
    Thread t = writerThread;
    if (t == null || t.getState() != Thread.State.TIMED_WAITING) {
      return false;
    }
    return Arrays.stream(t.getStackTrace())
        .anyMatch(
            f ->
                f.getClassName().equals(ResponseOutputStream.class.getName())
                    && f.getMethodName().equals("awaitWritable"));
  }

  private static void awaitTrue(BooleanSupplier condition, String message) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
    while (!condition.getAsBoolean()) {
      if (System.nanoTime() > deadline) {
        fail(message);
      }
      Thread.sleep(20);
    }
  }

  /** The status line under {@code :status}, then the headers with lower-cased names. */
  private static Map<String, String> readHead(InputStream in) throws IOException {
    Map<String, String> headers = new HashMap<>();
    headers.put(":status", readLine(in));
    String line;
    while (!(line = readLine(in)).isEmpty()) {
      int colon = line.indexOf(':');
      headers.put(
          line.substring(0, colon).trim().toLowerCase(Locale.ROOT), line.substring(colon + 1).trim());
    }
    return headers;
  }

  /** One chunk's data; an empty array for the terminating 0-chunk. EOF mid-framing throws. */
  private static byte[] readChunk(InputStream in) throws IOException {
    String sizeLine = readLine(in);
    int size = Integer.parseInt(sizeLine.split(";")[0].trim(), 16);
    byte[] data = in.readNBytes(size);
    if (data.length < size) {
      throw new EOFException("chunk cut short");
    }
    readLine(in); // CRLF after the data (and after the trailer section of the 0-chunk)
    return data;
  }

  private static byte[] readChunkedBody(InputStream in) throws IOException {
    ByteArrayOutputStream all = new ByteArrayOutputStream();
    byte[] chunk;
    while ((chunk = readChunk(in)).length > 0) {
      all.write(chunk);
    }
    return all.toByteArray();
  }

  private static String readLine(InputStream in) throws IOException {
    StringBuilder line = new StringBuilder();
    while (true) {
      int c = in.read();
      if (c < 0) {
        throw new EOFException("connection closed after: " + line);
      }
      if (c == '\n') {
        int len = line.length();
        if (len > 0 && line.charAt(len - 1) == '\r') {
          line.setLength(len - 1);
        }
        return line.toString();
      }
      line.append((char) c);
    }
  }
}
