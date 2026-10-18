package eu.wohlben.qits.githost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import eu.wohlben.qits.http.QitsHttpClients;
import io.opentelemetry.api.OpenTelemetry;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The HTTP adapter on its own, as a plain JUnit test rather than a {@code @QuarkusTest}: under CDI
 * {@link FakeRepositoryNameResolver} replaces it (an alternative of its type, standing in for the
 * fallback behind {@link StoredRepositoryNameResolver}), so the only way to exercise the real one is
 * to build it and set its two fields. Upstream is an in-process {@link
 * HttpServer}, the {@code StubIntake} / {@code StubNpmRegistry} idiom — there is no network here, and
 * a test that reached a real qits-projects would pass or fail for reasons unrelated to this code.
 *
 * <p><b>The two answers this adapter must never blur.</b> A 404 from qits-projects is empty — the
 * question was answered and the answer is "no such name", which the routes report as a 404. Anything
 * else that stops the question being answered throws {@link RepositoryNameResolver.Unavailable}, and
 * the routes report 503. An outage answered empty is {@code fe26a6c} again: a git client caches a
 * 404 as "this repository is gone".
 */
class HttpRepositoryNameResolverTest {

  private HttpServer upstream;
  private String base;

  /** The last path the stub was asked for, so the composed url is asserted rather than assumed. */
  private final AtomicReference<String> lastPath = new AtomicReference<>();

  private final AtomicInteger requests = new AtomicInteger();

  /** What the stub answers next: a status and a body. */
  private int status = 200;

  private String body = "{\"repositoryId\":\"repo-1\"}";

  @BeforeEach
  void startUpstream() throws Exception {
    upstream = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    upstream.createContext("/", this::answer);
    upstream.start();
    base = "http://127.0.0.1:" + upstream.getAddress().getPort() + "/projects/api/projects";
  }

  @AfterEach
  void stopUpstream() {
    upstream.stop(0);
  }

  private void answer(HttpExchange exchange) throws java.io.IOException {
    requests.incrementAndGet();
    lastPath.set(exchange.getRequestURI().getPath());
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().add("Content-Type", "application/json");
    exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
    if (bytes.length > 0) {
      try (OutputStream out = exchange.getResponseBody()) {
        out.write(bytes);
      }
    }
    exchange.close();
  }

  /** What expiry reads instead of the wall clock, so a TTL is crossed by assignment, not a sleep. */
  private final AtomicLong now = new AtomicLong();

  private HttpRepositoryNameResolver resolver(String url) {
    return resolver(url, Duration.ofMinutes(10));
  }

  private HttpRepositoryNameResolver resolver(String url, Duration ttl) {
    HttpRepositoryNameResolver resolver = new HttpRepositoryNameResolver();
    resolver.cache = new RepositoryNameCache(ttl, now::get);
    resolver.resolverUrl = Optional.ofNullable(url);
    resolver.objectMapper = new ObjectMapper();
    resolver.clients = QitsHttpClients.of(OpenTelemetry.noop(), null);
    resolver.buildClient();
    return resolver;
  }

  @Test
  void aConfiguredResolverAsksQitsProjectsAndReturnsTheId() {
    assertEquals(Optional.of("repo-1"), resolver(base).resolveRepositoryId("p-7", "testing-repo"));
    assertEquals(
        "/projects/api/projects/p-7/repositories/by-name/testing-repo",
        lastPath.get(),
        "the contract path, composed segment for segment");
  }

  @Test
  void aTrailingDotGitNeverReachesTheUrl() {
    // GitHostRoutes strips it first; this is the belt-and-braces strip, and it is what keeps a
    // caller that skipped that step from looking up a name qits-projects never registered.
    assertEquals(
        Optional.of("repo-1"), resolver(base).resolveRepositoryId("p-7", "testing-repo.git"));
    assertEquals("/projects/api/projects/p-7/repositories/by-name/testing-repo", lastPath.get());
  }

  @Test
  void anUnknownNameIsEmpty() {
    // The one negative answer that is an ANSWER: qits-projects looked and there is no such name.
    status = 404;
    body = "";
    assertEquals(Optional.empty(), resolver(base).resolveRepositoryId("p-7", "nope"));
  }

  @Test
  void aBrokenAnswerThrowsRatherThanReadingAsAMiss() {
    // None of these three is qits-projects saying "no such name": a 500 is a fault, and a 200 whose
    // body carries no id is an answer this adapter cannot read. Reported as an outage, they become a
    // 503 the caller retries; reported as empty, they become a 404 the caller believes.
    status = 500;
    body = "not json at all";
    assertThrows(
        RepositoryNameResolver.Unavailable.class,
        () -> resolver(base).resolveRepositoryId("p-7", "testing-repo"));

    status = 200;
    body = "{\"unexpected\":true}";
    assertThrows(
        RepositoryNameResolver.Unavailable.class,
        () -> resolver(base).resolveRepositoryId("p-7", "testing-repo"));

    body = "}{ garbage";
    assertThrows(
        RepositoryNameResolver.Unavailable.class,
        () -> resolver(base).resolveRepositoryId("p-7", "testing-repo"));
  }

  @Test
  void anUnreachableUpstreamThrowsAndIsBounded() {
    long startedAt = System.nanoTime();
    assertThrows(
        RepositoryNameResolver.Unavailable.class,
        () ->
            resolver("http://localhost:1/projects/api/projects")
                .resolveRepositoryId("p-7", "testing-repo"));
    Duration took = Duration.ofNanos(System.nanoTime() - startedAt);
    assertTrue(
        took.compareTo(HttpRepositoryNameResolver.REQUEST_TIMEOUT) <= 0,
        "an unreachable resolver must not outlast the request timeout, but took " + took);
  }

  @Test
  void withNoUrlConfiguredNothingIsDialled() {
    assertEquals(Optional.empty(), resolver(null).resolveRepositoryId("p-7", "testing-repo"));
    // The same for a configured-empty value, which SmallRye would hand over as absent anyway.
    assertEquals(Optional.empty(), resolver("  ").resolveRepositoryId("p-7", "testing-repo"));
    assertEquals(0, requests.get(), "an unconfigured resolver must make no request at all");
    assertNull(lastPath.get());
  }

  // --- the cache (qits-168) -----------------------------------------------------------------------
  // Every name-addressed fetch lands in the resolver, so an uncached adapter is a lookup per fetch.
  // What is held is a found id and nothing else: a 404 is re-asked so a new repository resolves at
  // once, and an outage is re-asked so a 503 is never served from memory.

  @Test
  void aFoundIdIsServedFromTheCacheWithoutAskingAgain() {
    HttpRepositoryNameResolver resolver = resolver(base);
    assertEquals(Optional.of("repo-1"), resolver.resolveRepositoryId("p-7", "testing-repo"));
    // Upstream changing its mind is invisible inside the TTL — which is the point, and the price.
    body = "{\"repositoryId\":\"repo-2\"}";
    assertEquals(Optional.of("repo-1"), resolver.resolveRepositoryId("p-7", "testing-repo"));
    assertEquals(
        Optional.of("repo-1"), resolver.resolveRepositoryId("p-7", "testing-repo.git"),
        "the .git spelling is the same name and the same entry");
    assertEquals(1, requests.get(), "one lookup, then memory");

    assertEquals(Optional.of("repo-2"), resolver.resolveRepositoryId("p-8", "testing-repo"));
    assertEquals(2, requests.get(), "a different project segment is a different key");
  }

  @Test
  void aMissIsNeverCachedSoANewRepositoryResolvesAtOnce() {
    HttpRepositoryNameResolver resolver = resolver(base);
    status = 404;
    body = "";
    assertEquals(Optional.empty(), resolver.resolveRepositoryId("p-7", "fresh"));
    status = 200;
    body = "{\"repositoryId\":\"repo-fresh\"}";
    assertEquals(Optional.of("repo-fresh"), resolver.resolveRepositoryId("p-7", "fresh"));
    assertEquals(2, requests.get());
  }

  @Test
  void anOutageIsNeverCached() {
    HttpRepositoryNameResolver resolver = resolver(base);
    status = 500;
    body = "down";
    assertThrows(
        RepositoryNameResolver.Unavailable.class,
        () -> resolver.resolveRepositoryId("p-7", "testing-repo"));
    status = 200;
    body = "{\"repositoryId\":\"repo-1\"}";
    assertEquals(Optional.of("repo-1"), resolver.resolveRepositoryId("p-7", "testing-repo"));
    assertEquals(2, requests.get(), "the failure was not remembered as anything");
  }

  @Test
  void anEntryExpiresAfterTheTtl() {
    HttpRepositoryNameResolver resolver = resolver(base, Duration.ofMinutes(10));
    resolver.resolveRepositoryId("p-7", "testing-repo");
    now.addAndGet(Duration.ofMinutes(10).minusNanos(1).toNanos());
    resolver.resolveRepositoryId("p-7", "testing-repo");
    assertEquals(1, requests.get(), "a nanosecond short of the TTL is still a hit");
    now.addAndGet(1);
    resolver.resolveRepositoryId("p-7", "testing-repo");
    assertEquals(2, requests.get(), "at the TTL the entry is gone and upstream is asked again");
  }

  @Test
  void aZeroTtlTurnsTheCacheOff() {
    HttpRepositoryNameResolver resolver = resolver(base, Duration.ZERO);
    resolver.resolveRepositoryId("p-7", "testing-repo");
    resolver.resolveRepositoryId("p-7", "testing-repo");
    assertEquals(2, requests.get(), "every lookup dials");
    assertEquals(0, resolver.cache.size());
  }

  @Test
  void evictingANameDropsItUnderEveryProjectSegment() {
    // The key is the segment the url carried — a project id or its slug — so a rename, which names
    // the id, evicts by name everywhere.
    HttpRepositoryNameResolver resolver = resolver(base);
    resolver.resolveRepositoryId("p-7", "testing-repo");
    resolver.resolveRepositoryId("my-project", "testing-repo");
    resolver.resolveRepositoryId("p-7", "other-repo");
    assertEquals(2, resolver.cache.evictName("testing-repo"));
    resolver.resolveRepositoryId("p-7", "testing-repo");
    resolver.resolveRepositoryId("p-7", "other-repo");
    assertEquals(4, requests.get(), "the evicted name asks again, the other is still a hit");
  }
}
