package eu.wohlben.qits.githost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.githost.contracts.ProviderGoldenMasters;
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
 * <p><b>One row, the JWKS.</b> application.properties sets {@code
 * quarkus.oidc.discovery-enabled=false} and {@code quarkus.oidc.jwks-path=jwks}, so the tenant never
 * reads the discovery document: it fetches {@code <auth-server-url>/jwks} when it starts (and again
 * on a key it does not know, the same request). {@link ConsumerGuardTest} holds the properties to
 * that, so a tenant that starts discovering fails there until a discovery row is added here.
 *
 * <p>This service mints no token: it has no {@code quarkus-oidc-client}, and its one peer call
 * ({@link HttpRepositoryNameResolver}) presents the in-network forward-auth headers instead.
 */
class IdpConsumerPactTest {

  static {
    // pact-jvm reports usage metrics over the network unless told not to; a test never should.
    System.setProperty("pact_do_not_track", "true");
  }

  static final String STATE = "a published signing key";

  static final GoldenMasters IDP = GoldenMasters.of("qits-idp-service", "qits-idp");

  static final GoldenInteraction JWKS =
      GoldenInteraction.of(Trigger.event("StartupEvent"), STATE, "getJwks")
          .consumes("keys[].kid", "keys[].kty", "keys[].n", "keys[].e", "keys[].alg", "keys[].use");

  static final ConsumerPact PACT = ConsumerPact.of(ProviderGoldenMasters.CONSUMER, IDP, JWKS);

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final HttpClient HTTP = HttpClient.newHttpClient();

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
