package eu.wohlben.qits.githost;

import io.quarkus.security.identity.IdentityProviderManager;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.security.identity.request.AuthenticationRequest;
import io.quarkus.security.runtime.QuarkusSecurityIdentity;
import io.quarkus.vertx.http.runtime.security.ChallengeData;
import io.quarkus.vertx.http.runtime.security.HttpAuthenticationMechanism;
import io.restassured.builder.RequestSpecBuilder;
import io.restassured.config.HeaderConfig;
import io.restassured.config.RestAssuredConfig;
import io.restassured.specification.RequestSpecification;
import io.smallrye.mutiny.Uni;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.json.Json;
import jakarta.json.JsonArrayBuilder;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A JWT identity for the {@code @QuarkusTest} suite, where {@code %test} turns the OIDC tenant off.
 *
 * <p>A request with {@value #HEADER}{@code : <claims as JSON>} is authenticated as a JWT principal
 * with those claims, and its {@code groups} become roles. Arrays arrive as a {@code
 * jakarta.json.JsonArray}, as quarkus-oidc hands them over. A request without the header carries
 * no identity: the suite runs without qits-auth-core's forward-auth and its {@code %test} dev user
 * (qits-1364), so nothing else authenticates it. A REST-assured request names its caller with
 * {@link #claims}.
 *
 * <p>The suite's git client presents {@link #SERVICE_CLIENT} by default ({@code GitHostFixture}
 * and the other test helpers): a client token without a scope, with {@code qits:admin} and {@code
 * qits:system}. Its pushes are therefore restricted exactly as much as they were before C3 — not at
 * all. A test that is about another credential names it.
 *
 * <p>Test code only. The packaged process that the ITs launch never contains it, and those stories
 * present real tokens from the mock idp.
 */
@ApplicationScoped
public class TestTokenMechanism implements HttpAuthenticationMechanism {

  public static final String HEADER = "X-Test-Token";

  /** A platform service client with no scope, holding {@code qits:admin} and {@code qits:system}. */
  public static final String SERVICE_CLIENT_CLAIMS =
      "{\"sub\":\"qits-githost-suite\","
          + "\"groups\":[\"qits:admin\",\"qits:system\"]}";

  /** A platform service's client token: {@code qits:system} and nothing else. */
  public static final String PLATFORM_SERVICE_CLAIMS = claims("a-platform-service", "qits:system");

  /** A signed-in person on the browse plane: {@code qits:admin} and nothing else. */
  public static final String OPERATOR_CLAIMS = claims("operator", "qits:admin");

  /** The headers the suite's git client sends unless a test says otherwise. */
  public static final List<String> SERVICE_CLIENT = token(SERVICE_CLIENT_CLAIMS);

  /**
   * The {@value #HEADER} value of a client token for {@code sub} holding {@code roles}
   * (comma-separated): what a REST-assured request sends to name its identity.
   */
  public static String claims(String sub, String roles) {
    JsonArray groups = new JsonArray();
    for (String role : roles.split(",")) {
      if (!role.isBlank()) {
        groups.add(role.trim());
      }
    }
    return new JsonObject().put("sub", sub).put("groups", groups).encode();
  }

  /**
   * A REST-assured default specification that names {@code claimsJson} as every request's caller:
   * what a class installs as {@code RestAssured.requestSpecification} in a {@code @BeforeEach}
   * (and clears in an {@code @AfterEach}). A request that sets {@value #HEADER} itself replaces the
   * default rather than sending both.
   */
  public static RequestSpecification defaultCaller(String claimsJson) {
    return new RequestSpecBuilder()
        .addHeader(HEADER, claimsJson)
        .setConfig(
            RestAssuredConfig.config()
                .headerConfig(HeaderConfig.headerConfig().overwriteHeadersWithName(HEADER)))
        .build();
  }

  /** The header list that presents a JWT with {@code claimsJson}. */
  public static List<String> token(String claimsJson) {
    return List.of(HEADER + ": " + claimsJson);
  }

  /**
   * The environment that makes {@code git} send {@code headers} on every request: one {@code
   * http.extraHeader} each, through {@code GIT_CONFIG_COUNT}. An empty list sends none.
   */
  public static Map<String, String> gitEnvironment(List<String> headers) {
    Map<String, String> environment = new LinkedHashMap<>();
    environment.put("GIT_CONFIG_COUNT", String.valueOf(headers.size()));
    for (int i = 0; i < headers.size(); i++) {
      environment.put("GIT_CONFIG_KEY_" + i, "http.extraHeader");
      environment.put("GIT_CONFIG_VALUE_" + i, headers.get(i));
    }
    return environment;
  }

  /** Makes a test's own {@code ProcessBuilder} present {@link #SERVICE_CLIENT}. */
  public static void presentServiceClient(ProcessBuilder builder) {
    builder.environment().putAll(gitEnvironment(SERVICE_CLIENT));
  }

  @Override
  public Uni<SecurityIdentity> authenticate(
      RoutingContext context, IdentityProviderManager identityProviderManager) {
    String raw = context.request().getHeader(HEADER);
    if (raw == null || raw.isBlank()) {
      return Uni.createFrom().nullItem();
    }
    return Uni.createFrom().item(identity(new JsonObject(raw)));
  }

  /** The identity a real OIDC tenant would make of these claims. */
  public static SecurityIdentity identity(JsonObject token) {
    Map<String, Object> claims = new LinkedHashMap<>();
    for (String name : token.fieldNames()) {
      claims.put(name, claimValue(token.getValue(name)));
    }
    QuarkusSecurityIdentity.Builder identity =
        QuarkusSecurityIdentity.builder()
            .setPrincipal(new FakeJwt(token.getString("sub", "test-client"), claims));
    JsonArray groups = token.getJsonArray("groups");
    if (groups != null) {
      groups.forEach(group -> identity.addRole(String.valueOf(group)));
    }
    return identity.build();
  }

  /** A JSON array becomes a {@code jakarta.json.JsonArray}; anything else stays as it is. */
  private static Object claimValue(Object value) {
    if (!(value instanceof JsonArray array)) {
      return value;
    }
    JsonArrayBuilder builder = Json.createArrayBuilder();
    for (Object entry : array) {
      if (entry instanceof String string) {
        builder.add(string);
      } else if (entry instanceof Number number) {
        builder.add(number.longValue());
      } else if (entry == null) {
        builder.addNull();
      } else {
        builder.add(String.valueOf(entry));
      }
    }
    return builder.build();
  }

  @Override
  public Uni<ChallengeData> getChallenge(RoutingContext context) {
    return Uni.createFrom().item(new ChallengeData(401, null, null));
  }

  @Override
  public Set<Class<? extends AuthenticationRequest>> getCredentialTypes() {
    return Set.of();
  }

  @Override
  public int getPriority() {
    return DEFAULT_PRIORITY + 1000;
  }
}
