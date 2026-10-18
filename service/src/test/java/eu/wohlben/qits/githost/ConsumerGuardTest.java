package eu.wohlben.qits.githost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import eu.wohlben.qits.pact.consumer.GoldenInteraction;
import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * <b>Every outbound qits call this service makes has a pact row</b> (ticket qits-1149) — the guard
 * that keeps {@link IdpConsumerPactTest} complete, and that keeps this service from growing a peer
 * call nobody pacted. The set of calls is read off the code, never written down here:
 *
 * <ol>
 *   <li><b>Which classes call out.</b> Every compiled main class is scanned for a reference to an
 *       HTTP client type ({@link #CLIENT_TYPES}). Each such class must be a named {@link #EXCLUDED}
 *       one. There is no client of this service's own today: the one there was, qits-projects'
 *       by-name lookup, went when the git host started keeping repository names itself
 *       (qits-1215). A new client class fails here until it has a consumer pact of its own and the
 *       per-request check that went with that lookup is brought back for it (see this file's
 *       history).
 *   <li><b>What the libraries send for it.</b> quarkus-oidc's reads follow from
 *       application.properties: an enabled tenant needs the JWKS row, discovery enabled would need a
 *       discovery row, and a {@code quarkus-oidc-client} on the classpath (a token mint) would need
 *       a token row.
 * </ol>
 *
 * <p><b>Named exclusions</b>, and only these: the qits-eventstream library's publish ({@code PUT
 * /events/api/events/{id}}, through {@code QitsEventBus}), which ticket qits-1163 pacts per
 * consuming service as {@code <consumer>_qits-eventstream-javalib_qits-events-service.json} from the
 * library's own contract rows; the library's {@code /events/stream} websocket, which pact V4 cannot
 * describe; and the health probe, which nothing here calls.
 */
class ConsumerGuardTest {

  /** A class referencing one of these makes HTTP calls of its own (internal binary names). */
  static final List<String> CLIENT_TYPES =
      List.of(
          "java/net/http/HttpClient",
          "java/net/HttpURLConnection",
          "eu/wohlben/qits/http/QitsHttpClients",
          "org/eclipse/microprofile/rest/client",
          "jakarta/ws/rs/client",
          "io/vertx/ext/web/client",
          "io/vertx/core/http/HttpClient",
          "io/quarkus/oidc/client",
          "io/quarkus/websockets/next/BasicWebSocketConnector",
          "eu/wohlben/qits/eventstream/QitsEventBus",
          "eu/wohlben/qits/eventstream/control/EventsPublisher");

  /** Classes that make a qits call this guard does not row, each with the reason. */
  static final Map<String, String> EXCLUDED =
      Map.of(
          "eu.wohlben.qits.githost.bus.ScmEventAnnouncer",
          "publishes through qits-eventstream's QitsEventBus; the library's PUT"
              + " /events/api/events/{id} is pacted per consuming service by ticket qits-1163");

  @Test
  void everyClassThatCallsOutIsNamedAsExcluded() throws IOException {
    Set<String> callers = callers();
    assertFalse(callers.isEmpty(), "the scan found no caller at all: is it reading the main classes?");
    Set<String> unknown = new TreeSet<>(callers);
    unknown.removeAll(EXCLUDED.keySet());
    assertTrue(
        unknown.isEmpty(),
        "these classes make HTTP calls with no pact row: " + unknown
            + " — add a consumer pact (and a per-request check here) for them, or name them in"
            + " EXCLUDED with why");
    Set<String> stale = new TreeSet<>(EXCLUDED.keySet());
    stale.removeAll(callers);
    assertTrue(stale.isEmpty(), "no longer call out, drop them here: " + stale);
  }

  @Test
  void theOidcTenantReadsAreRowed() throws IOException {
    Properties properties = new Properties();
    // The main one, not the test classpath's overrides: the deployed tenant is the one that calls.
    try (InputStream in = Files.newInputStream(mainClasses().resolve("application.properties"))) {
      properties.load(in);
    }
    Set<String> rowed =
        IdpConsumerPactTest.PACT.rows().stream()
            .map(GoldenInteraction::operationId)
            .collect(Collectors.toCollection(TreeSet::new));
    if (Boolean.parseBoolean(properties.getProperty("quarkus.oidc.tenant-enabled", "true"))) {
      assertTrue(rowed.contains("getJwks"), "the tenant reads the JWKS: row getJwks");
      if (Boolean.parseBoolean(properties.getProperty("quarkus.oidc.discovery-enabled", "true"))) {
        assertTrue(
            rowed.contains("getOpenIdConfiguration"),
            "the tenant discovers: row getOpenIdConfiguration in IdpConsumerPactTest");
      } else {
        assertFalse(
            rowed.contains("getOpenIdConfiguration"),
            "discovery is off, so the tenant never reads the discovery document: drop its row");
      }
    }
    assertEquals(
        Set.of(),
        properties.stringPropertyNames().stream()
            .filter(name -> name.startsWith("quarkus.oidc-client."))
            .collect(Collectors.toSet()),
        "a quarkus-oidc-client mints tokens at qits-idp: row POST /idp/token in IdpConsumerPactTest");
    try {
      Class.forName("io.quarkus.oidc.client.OidcClient");
      fail("quarkus-oidc-client is on the classpath: row its POST /idp/token in IdpConsumerPactTest");
    } catch (ClassNotFoundException expected) {
      // no token mint: nothing to row
    }
  }

  /** The main classes that reference an HTTP client type, by class name (nested ones folded in). */
  static Set<String> callers() throws IOException {
    Path root = mainClasses();
    Set<String> callers = new TreeSet<>();
    try (Stream<Path> files = Files.walk(root)) {
      for (Path file : files.filter(f -> f.toString().endsWith(".class")).toList()) {
        String bytes = new String(Files.readAllBytes(file), StandardCharsets.ISO_8859_1);
        if (CLIENT_TYPES.stream().anyMatch(bytes::contains)) {
          String name = root.relativize(file).toString().replace('\\', '/');
          name = name.substring(0, name.length() - ".class".length()).replace('/', '.');
          int nested = name.indexOf('$');
          callers.add(nested < 0 ? name : name.substring(0, nested));
        }
      }
    }
    return callers;
  }

  /** The compiled main classes: where {@link GitHostRoutes} was loaded from. */
  private static Path mainClasses() {
    try {
      return Path.of(
          GitHostRoutes.class.getProtectionDomain().getCodeSource().getLocation().toURI());
    } catch (URISyntaxException e) {
      throw new IllegalStateException(e);
    }
  }
}
