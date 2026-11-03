package eu.wohlben.qits.githost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import au.com.dius.pact.consumer.ConsumerPactRunnerKt;
import au.com.dius.pact.consumer.PactVerificationResult;
import au.com.dius.pact.consumer.model.MockProviderConfig;
import au.com.dius.pact.core.model.PactSpecVersion;
import io.quarkus.oidc.client.NamedOidcClient;
import io.quarkus.oidc.client.OidcClient;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * <b>The consumer half of the token mint</b> (qits-1330): the named client {@code qits} that
 * application.properties ships, switched on and pointed at a pact mock server that answers what
 * {@link IdpConsumerPactTest#MINT} promises — and the access token it hands out compared with what
 * the mock answered. Every other property of the client (grant type, token path, discovery off, the
 * {@code qits-platform} audience) is the shipped one, so the request the mock checks is the one the
 * deployed process sends.
 *
 * <p>A {@code @QuarkusTest}, unlike the tenant rows: the client is built from config at boot, so the
 * mock runs on a port this profile picks before the application starts. The port is a system
 * property rather than a static field, because Quarkus instantiates the profile in its own class
 * loader, where a static field is a second copy with a second free port.
 */
@QuarkusTest
@TestProfile(IdpTokenMintPactTest.MockIdp.class)
class IdpTokenMintPactTest {

  static {
    System.setProperty("pact_do_not_track", "true");
  }

  static final String PORT_PROPERTY = "qits.githost.contract.idp-mock-port";

  /** The {@code qits} client switched on and pointed at the mock, as the state's client. */
  public static class MockIdp implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
      Map<String, String> params =
          IdpConsumerPactTest.IDP.params(IdpConsumerPactTest.MINT.state());
      // The state hands its client as the Basic credential it sends: id and secret, decoded.
      String basic = params.get("authorization").substring("Basic ".length());
      String[] pair =
          new String(Base64.getDecoder().decode(basic), StandardCharsets.UTF_8).split(":", 2);
      return Map.of(
          "quarkus.oidc-client.qits.client-enabled", "true",
          "quarkus.oidc-client.qits.auth-server-url", "http://127.0.0.1:" + port() + "/idp",
          "quarkus.oidc-client.qits.client-id", pair[0],
          "quarkus.oidc-client.qits.credentials.secret", pair[1]);
    }
  }

  @Inject
  @NamedOidcClient("qits")
  OidcClient client;

  @Test
  void theQitsClientMintsTheTokenTheIdpAnswers() {
    String minted =
        IdpConsumerPactTest.IDP
            .json(IdpConsumerPactTest.MINT.state(), IdpConsumerPactTest.MINT.operationId())
            .path("access_token")
            .asText();
    PactVerificationResult result =
        ConsumerPactRunnerKt.runConsumerTest(
            IdpConsumerPactTest.PACT.pact(List.of(IdpConsumerPactTest.MINT)),
            MockProviderConfig.Companion.httpConfig("127.0.0.1", port(), PactSpecVersion.V4),
            (mockServer, context) -> {
              assertEquals(
                  minted,
                  client.getTokens().await().atMost(Duration.ofSeconds(20)).getAccessToken());
              return null;
            });
    assertInstanceOf(PactVerificationResult.Ok.class, result, String.valueOf(result));
  }

  /** The port the pact mock server listens on: chosen free once per JVM, whichever loader asks. */
  static synchronized int port() {
    String parked = System.getProperty(PORT_PROPERTY);
    if (parked != null) {
      return Integer.parseInt(parked);
    }
    try (ServerSocket socket = new ServerSocket(0)) {
      int port = socket.getLocalPort();
      System.setProperty(PORT_PROPERTY, String.valueOf(port));
      return port;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
