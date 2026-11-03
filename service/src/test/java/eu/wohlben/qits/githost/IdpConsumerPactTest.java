package eu.wohlben.qits.githost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.pact.consumer.ConsumerPact;
import eu.wohlben.qits.pact.consumer.GoldenInteraction;
import eu.wohlben.qits.pact.consumer.GoldenMasters;
import eu.wohlben.qits.pact.consumer.Trigger;
import java.math.BigInteger;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.KeyFactory;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.RSAPublicKeySpec;
import java.util.Base64;
import org.junit.jupiter.api.Test;

/**
 * <b>The consumer pact against qits-idp</b> (ticket qits-1149), {@code
 * pacts/qits-githost-service_qits-idp-service.json}: what the quarkus-oidc tenant that validates
 * every bearer reads, the way qits-configuration-service's test of the same name pacts its own.
 *
 * <p><b>Two rows, the tenant's two startup reads</b> (qits-708). Discovery is on — the default, and
 * what application.properties ships — so the tenant first reads {@code
 * <auth-server-url>/.well-known/openid-configuration}, takes from it the {@code issuer} every
 * bearer's {@code iss} is checked against and the {@code jwks_uri} it then fetches the keys from (and
 * again on a key it does not know, the same request). The issuer is bound <b>by value</b>: an idp
 * answering another issuer would refuse every token this service accepts, so its type alone is not
 * the contract. {@link ConsumerGuardTest} holds the properties to these rows, so turning discovery
 * off again, or pinning an issuer in configuration, fails there.
 *
 * <p><b>And the token this service presents</b> (qits-1330): the client-credentials mint of the
 * named client {@code qits} — {@code POST /idp/token} with HTTP Basic, {@code
 * grant_type=client_credentials} and {@code audience=qits-platform} — recorded by qits-idp as {@code
 * issueToken} in {@value #A_SERVICE_CLIENT_CALLING_ITS_PEERS}. No class of this source tree spends
 * the token: the qits-eventstream library's calls do, asking {@code OidcClients} for {@code qits}
 * (qits-1328), so that is the row's trigger. {@link IdpTokenMintPactTest} runs it through the real
 * oidc client, because the client only exists inside the application.
 */
class IdpConsumerPactTest {

  static {
    // pact-jvm reports usage metrics over the network unless told not to; a test never should.
    System.setProperty("pact_do_not_track", "true");
  }

  static final String STATE = "a published signing key";

  static final GoldenMasters IDP = GoldenMasters.of("qits-idp-service", "qits-idp");

  /** The quarkus-oidc tenant's startup reads, as qits-idp's consumers trigger them. */
  static final Trigger STARTUP = Trigger.event("StartupEvent");

  /** The discovery document: the issuer every {@code iss} is checked against, and the keys. */
  static final GoldenInteraction DISCOVERY =
      GoldenInteraction.of(STARTUP, STATE, "getOpenIdConfiguration")
          .consumes("issuer", "jwks_uri", "token_endpoint")
          .exact("issuer");

  /** The qits-idp state that records the client-credentials mint of a service client. */
  static final String A_SERVICE_CLIENT_CALLING_ITS_PEERS = "a service client calling its peers";

  /**
   * The mint, caused by whichever qits-eventstream call needs a bearer first (qits-1328): the
   * subscriber's stream, the publisher's PUT or the query's read.
   */
  static final GoldenInteraction MINT =
      GoldenInteraction.of(
              Trigger.of("call", "call", "qits-eventstream (subscriber, publisher, query)"),
              A_SERVICE_CLIENT_CALLING_ITS_PEERS,
              "issueToken")
          .header("Authorization", "{authorization}")
          .consumes("access_token", "expires_in");

  static final GoldenInteraction JWKS =
      GoldenInteraction.of(STARTUP, STATE, "getJwks")
          .consumes("keys[].kid", "keys[].kty", "keys[].n", "keys[].e", "keys[].alg", "keys[].use");

  /** This repository, as every pact names its consumer. */
  static final String CONSUMER = "qits-githost-service";

  static final ConsumerPact PACT = ConsumerPact.of(CONSUMER, IDP, MINT, DISCOVERY, JWKS);

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final HttpClient HTTP = HttpClient.newHttpClient();

  @Test
  void readsTheDiscoveryDocument() {
    PACT.run(
        DISCOVERY,
        (url, recorded) -> {
          JsonNode document = get(url + recorded.examplePath());
          assertEquals(
              IDP.json(STATE, "getOpenIdConfiguration").path("issuer").asText(),
              document.path("issuer").asText(),
              "the issuer every bearer's iss is checked against");
          URI.create(document.path("jwks_uri").asText());
          URI.create(document.path("token_endpoint").asText());
        });
  }

  @Test
  void readsTheSigningKeys() {
    PACT.run(
        JWKS,
        (url, recorded) -> {
          JsonNode keys = get(url + recorded.examplePath()).path("keys");
          assertFalse(keys.isEmpty(), "at least one key");
          for (JsonNode key : keys) {
            assertEquals("RSA", key.path("kty").asText());
            assertEquals("sig", key.path("use").asText());
            assertEquals("RS256", key.path("alg").asText());
            assertFalse(key.path("kid").asText().isBlank(), "kid");
            // What the tenant does with a key: build an RSA public key from n and e.
            Base64.Decoder b64 = Base64.getUrlDecoder();
            RSAPublicKey rsa =
                (RSAPublicKey)
                    KeyFactory.getInstance("RSA")
                        .generatePublic(
                            new RSAPublicKeySpec(
                                new BigInteger(1, b64.decode(key.path("n").asText())),
                                new BigInteger(1, b64.decode(key.path("e").asText()))));
            assertTrue(rsa.getModulus().bitLength() >= 2048, "an RSA key of 2048 bits or more");
          }
        });
  }

  @Test
  void theCommittedPactIsWhatTheRowsWrite() {
    PACT.assertEveryInteractionCarriesBothReferences();
    PACT.compareOrWritePactFile();
  }

  private static JsonNode get(String url) throws Exception {
    HttpResponse<String> response =
        HTTP.send(
            HttpRequest.newBuilder(URI.create(url)).header("Accept", "application/json").build(),
            HttpResponse.BodyHandlers.ofString());
    assertEquals(200, response.statusCode(), url);
    return MAPPER.readTree(response.body());
  }
}
