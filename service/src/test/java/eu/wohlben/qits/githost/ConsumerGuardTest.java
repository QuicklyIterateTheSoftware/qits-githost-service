package eu.wohlben.qits.githost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.sun.net.httpserver.HttpServer;
import eu.wohlben.qits.pact.consumer.GoldenInteraction;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.InetSocketAddress;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * <b>Every outbound qits call this service makes has a pact row</b> (ticket qits-1149) — the guard
 * that keeps {@link ProjectsContract} and {@link IdpConsumerPactTest} complete. The set of calls is
 * read off the code, never written down here:
 *
 * <ol>
 *   <li><b>Which classes call out.</b> Every compiled main class is scanned for a reference to an
 *       HTTP client type ({@link #CLIENT_TYPES}). Each such class must be a {@link #CLIENTS} entry
 *       or a named {@link #EXCLUDED} one; a new client class fails here until it has rows.
 *   <li><b>What each client sends.</b> Every method of the client's port is called against a
 *       capturing HTTP server, and every request it makes must match a row of its contract by
 *       method and path. A new port method, or a new request inside an old one, fails here.
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

  /** One client class: the port the guard drives it through, and its contract's rows. */
  record Client(
      Class<?> port,
      Function<String, Object> againstBase,
      List<ProjectsContract.Case> rows) {}

  static final Map<String, Client> CLIENTS =
      Map.of(
          HttpRepositoryNameResolver.class.getName(),
          new Client(
              RepositoryNameResolver.class,
              base -> ProjectsContract.resolverAgainst(base),
              ProjectsContract.CASES));

  /** Classes that make a qits call this guard does not row, each with the reason. */
  static final Map<String, String> EXCLUDED =
      Map.of(
          "eu.wohlben.qits.githost.bus.ScmEventAnnouncer",
          "publishes through qits-eventstream's QitsEventBus; the library's PUT"
              + " /events/api/events/{id} is pacted per consuming service by ticket qits-1163");

  @Test
  void everyClassThatCallsOutIsAClientWithRowsOrNamedAsExcluded() throws IOException {
    Set<String> callers = callers();
    assertFalse(callers.isEmpty(), "the scan found no caller at all: is it reading the main classes?");
    Set<String> unknown = new TreeSet<>(callers);
    unknown.removeAll(CLIENTS.keySet());
    unknown.removeAll(EXCLUDED.keySet());
    assertTrue(
        unknown.isEmpty(),
        "these classes make HTTP calls with no pact row: " + unknown
            + " — add a contract (and a CLIENTS entry) for them, or name them in EXCLUDED with why");
    Set<String> stale = new TreeSet<>(CLIENTS.keySet());
    stale.addAll(EXCLUDED.keySet());
    stale.removeAll(callers);
    assertTrue(stale.isEmpty(), "no longer call out, drop them here: " + stale);
  }

  @Test
  void everyRequestAClientSendsHasARow() throws Exception {
    List<String> failures = new ArrayList<>();
    for (Map.Entry<String, Client> entry : CLIENTS.entrySet()) {
      Client client = entry.getValue();
      List<String> captured = new ArrayList<>();
      HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      server.createContext(
          "/",
          exchange -> {
            synchronized (captured) {
              captured.add(exchange.getRequestMethod() + " " + exchange.getRequestURI().getRawPath());
            }
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
          });
      server.start();
      try {
        Object adapter =
            client.againstBase().apply("http://127.0.0.1:" + server.getAddress().getPort());
        for (Method method : client.port().getMethods()) {
          if (Modifier.isStatic(method.getModifiers()) || method.isDefault()) {
            continue;
          }
          int before = captured.size();
          method.invoke(adapter, arguments(method));
          if (captured.size() == before) {
            failures.add(entry.getKey() + "." + method.getName() + " sent no request the guard saw");
          }
        }
      } finally {
        server.stop(0);
      }
      for (String request : captured) {
        String verb = request.substring(0, request.indexOf(' '));
        String path = request.substring(request.indexOf(' ') + 1);
        boolean rowed =
            client.rows().stream()
                .anyMatch(
                    row ->
                        row.call().method().equals(verb)
                            && template(row.call().path()).matcher(path).matches());
        if (!rowed) {
          failures.add(entry.getKey() + " sent " + request + ", which no contract row describes");
        }
      }
    }
    if (!failures.isEmpty()) {
      fail(String.join("\n", failures));
    }
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

  /** The compiled main classes: where {@link HttpRepositoryNameResolver} was loaded from. */
  private static Path mainClasses() {
    try {
      return Path.of(
          HttpRepositoryNameResolver.class.getProtectionDomain().getCodeSource().getLocation().toURI());
    } catch (URISyntaxException e) {
      throw new IllegalStateException(e);
    }
  }

  /** A row's path template as a regex: each {@code {param}} one path segment. */
  private static Pattern template(String path) {
    StringBuilder out = new StringBuilder();
    for (String part : path.split("(?=\\{)|(?<=})")) {
      out.append(part.startsWith("{") ? "[^/]+" : Pattern.quote(part));
    }
    return Pattern.compile(out.toString());
  }

  /** A plain value for every parameter; a port that takes anything else needs teaching here. */
  private static Object[] arguments(Method method) {
    Object[] args = new Object[method.getParameterCount()];
    Class<?>[] types = method.getParameterTypes();
    for (int i = 0; i < args.length; i++) {
      if (types[i] != String.class) {
        throw new IllegalStateException(
            method + " takes a " + types[i].getSimpleName() + ": teach the guard a value for it");
      }
      args[i] = "contract-" + i;
    }
    return args;
  }
}
