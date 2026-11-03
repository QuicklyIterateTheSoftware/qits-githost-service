package eu.wohlben.qits.githost.api;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.servicemock.idp.MockIdp;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * <b>Only the issuer qits-idp publishes is accepted</b> (qits-708). The shipped tenant has discovery
 * on and no issuer key at all, so the issuer quarkus-oidc checks every bearer's {@code iss} against
 * is the {@code issuer} of the discovery document it reads at startup. Before qits-708 discovery
 * was off and no issuer was configured, and quarkus-oidc then checks no {@code iss} at all: a token
 * signed by a published key and addressed to {@code qits-platform} was accepted whoever claimed to
 * have issued it.
 *
 * <p>quarkus-oidc is the one bearer verifier in this service, so both doors are asked: the git wire
 * ({@code /git}, the {@code git} path policy) and the REST API ({@code /githost/api/loc}, the
 * {@code githost-browse} policy). {@code BootstrapIngressCredential} is no JWT and is out of scope.
 *
 * <p>The far side is qits-service-mock's {@link MockIdp}: it serves a discovery document whose
 * {@code issuer} is the {@code iss} it mints, and a JWKS at the {@code jwks_uri} that document
 * names. {@code %test} turns the tenant off and opens the git policy, so this profile turns both back
 * to what ships and points {@code auth-server-url} at the mock — nothing else
 * moves, so the tenant under test is the shipped one: discovery, the published issuer, the shipped
 * audience, keys by {@code kid}.
 *
 * <p>{@code TokenValidationBootstrapIT} proves the same block on the packaged artifact; this class
 * is the in-JVM proof of the refusal, which no other test here makes.
 */
@QuarkusTest
@TestProfile(BearerIssuerTest.DiscoveringTheMockIdp.class)
class BearerIssuerTest {

  /** The git wire's listing: the {@code git} path policy, qits:system admitted. */
  private static final String GIT_ROUTE = "/git";

  /** A REST read behind the {@code githost-browse} path policy, qits:system admitted. */
  private static final String API_ROUTE = "/githost/api/loc";

  /** The one platform audience, which the shipped tenant enforces. */
  private static final String AUDIENCE = "qits-platform";

  /** Another environment's idp address: an issuer this service trusts under no name. */
  private static final String FOREIGN_ISSUER = "http://dev-qits-idp:8080/idp";

  /** The tenant on and pointed at the mock idp, the shipped git policy. */
  public static class DiscoveringTheMockIdp implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
      MockIdp idp = MockIdp.ensureStarted();
      return Map.of(
          "quarkus.oidc.tenant-enabled", "true",
          "qits.auth.machine.required", "true",
          "quarkus.http.auth.permission.git.policy", "git",
          "quarkus.oidc.auth-server-url", idp.baseUrl());
    }
  }

  @Test
  void theTenantReadsTheDiscoveryDocument() {
    // The tenant resolves at startup (connection-delay): the document is read before any bearer.
    given().when().get("/githost/q/health/ready").then().statusCode(200);
    assertTrue(
        MockIdp.attach().recordedRequests().stream()
            .anyMatch(r -> "/idp/.well-known/openid-configuration".equals(r.path())),
        "the tenant never read the idp's discovery document");
  }

  @Test
  void aBearerFromThePublishedIssuerIsAccepted() {
    String token = MockIdp.attach().token().audience(AUDIENCE).groups("qits:system").mint();
    given().header("Authorization", "Bearer " + token).when().get(GIT_ROUTE).then().statusCode(200);
    given().header("Authorization", "Bearer " + token).when().get(API_ROUTE).then().statusCode(200);
  }

  @Test
  void aBearerFromAnotherIssuerIsRefused() {
    // Correctly signed with the published key, the right audience, not expired: only `iss` is wrong.
    String token =
        MockIdp.attach()
            .token()
            .audience(AUDIENCE)
            .groups("qits:system")
            .claim("iss", FOREIGN_ISSUER)
            .mint();
    given().header("Authorization", "Bearer " + token).when().get(GIT_ROUTE).then().statusCode(401);
    given().header("Authorization", "Bearer " + token).when().get(API_ROUTE).then().statusCode(401);
  }
}
