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
 * <p>This service mints no token: it has no {@code quarkus-oidc-client}, and it makes no peer call
 * that would present one — it answers repository names from its own index rather than asking
 * qits-projects (qits-1215).
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

  static final GoldenInteraction JWKS =
      GoldenInteraction.of(STARTUP, STATE, "getJwks")
          .consumes("keys[].kid", "keys[].kty", "keys[].n", "keys[].e", "keys[].alg", "keys[].use");

  /** This repository, as every pact names its consumer. */
  static final String CONSUMER = "qits-githost-service";

  static final ConsumerPact PACT = ConsumerPact.of(CONSUMER, IDP, DISCOVERY, JWKS);

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
