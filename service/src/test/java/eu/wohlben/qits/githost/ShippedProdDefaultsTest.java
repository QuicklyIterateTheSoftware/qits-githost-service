package eu.wohlben.qits.githost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.smallrye.config.EnvConfigSource;
import io.smallrye.config.PropertiesConfigSource;
import io.smallrye.config.SmallRyeConfig;
import io.smallrye.config.SmallRyeConfigBuilder;
import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import org.junit.jupiter.api.Test;

/**
 * What a DEPLOYED process resolves once the deployer stops handing it qits-configuration's extras
 * (qits-1391): only {@code QITS_ENVIRONMENT} from the deployer, and everything else from the shipped
 * {@code application.properties}. Each assertion is a value the deployer used to state as an {@code
 * env.*} extra on dev-qits-githost, so a release that moved one of them would change a live value.
 *
 * <p>Assembled by hand from the shipped file at the ordinal a packaged jar gives it (250) plus an
 * env source at 300, because the classpath copy is shadowed by the test {@code
 * application.properties} and a {@code @QuarkusTest} only ever runs {@code test}.
 */
class ShippedProdDefaultsTest {

  private static URL shippedProperties() {
    Path path = Path.of("src/main/resources/application.properties");
    if (!Files.isRegularFile(path)) {
      path = Path.of("service/src/main/resources/application.properties");
    }
    assertTrue(Files.isRegularFile(path), "cannot find the shipped application.properties");
    try {
      return path.toUri().toURL();
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  private static SmallRyeConfig shipped(String profile, Map<String, String> env) {
    try {
      return new SmallRyeConfigBuilder()
          .addDefaultInterceptors()
          .withProfile(profile)
          .withSources(new PropertiesConfigSource(shippedProperties(), 250))
          .withSources(new EnvConfigSource(env, 300))
          .build();
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  @Test
  void aDeployedProcessResolvesEveryRetiredExtraToItsLiveValue() {
    SmallRyeConfig config = shipped("prod", Map.of("QITS_ENVIRONMENT", "dev"));
    assertEquals(
        "dev-qits-projects", config.getValue("qits.githost.storage-client", String.class));
    assertEquals(
        "true", config.getValue("qits.repositories.git.protect-default-branch", String.class));
    assertEquals("true", config.getValue("qits.auth.machine.required", String.class));
  }

  /**
   * The qits client's idp address, where the deployer injected no {@code QITS_RESOURCE_IDP_URL}:
   * the idp through the edge, composed from {@code QITS_DOMAIN} — never the {@code <env>-qits-idp}
   * wire alias (owner ruling on qits-533, qits-1330).
   */
  @Test
  void theQitsClientFallsBackToTheIdpThroughTheEdge() {
    SmallRyeConfig config =
        shipped("prod", Map.of("QITS_ENVIRONMENT", "dev", "QITS_DOMAIN", "example.test"));
    assertEquals(
        "https://idp.qits.example.test/idp",
        config.getValue("quarkus.oidc-client.qits.auth-server-url", String.class));
  }

  /**
   * The tenant that validates every bearer reads the idp at its edge host, composed from {@code
   * QITS_DOMAIN} — never the {@code <env>-qits-idp} wire alias (qits-1421, feature qits-1406).
   */
  @Test
  void theTenantReadsTheIdpThroughTheEdge() {
    SmallRyeConfig config =
        shipped("prod", Map.of("QITS_ENVIRONMENT", "dev", "QITS_DOMAIN", "example.test"));
    assertEquals(
        "https://idp.qits.example.test/idp",
        config.getValue("quarkus.oidc.auth-server-url", String.class));
  }

  /** No domain, no idp address: the expression has no fallback, so the process cannot start. */
  @Test
  void withoutADomainNeitherIdpAddressResolves() {
    SmallRyeConfig config = shipped("prod", Map.of("QITS_ENVIRONMENT", "dev"));
    for (String key :
        List.of("quarkus.oidc.auth-server-url", "quarkus.oidc-client.qits.auth-server-url")) {
      assertThrows(
          NoSuchElementException.class, () -> config.getValue(key, String.class), key);
    }
  }

  @Test
  void theDeployersIdpAddressWinsOverTheFallback() {
    SmallRyeConfig config =
        shipped(
            "prod",
            Map.of(
                "QITS_ENVIRONMENT", "dev",
                "QITS_DOMAIN", "example.test",
                "QITS_RESOURCE_IDP_URL", "https://idp.qits.elsewhere.test/idp"));
    assertEquals(
        "https://idp.qits.elsewhere.test/idp",
        config.getValue("quarkus.oidc-client.qits.auth-server-url", String.class));
  }

  @Test
  void theStorageClientFollowsTheEnvironment() {
    SmallRyeConfig config = shipped("prod", Map.of("QITS_ENVIRONMENT", "prod"));
    assertEquals(
        "prod-qits-projects", config.getValue("qits.githost.storage-client", String.class));
  }

  @Test
  void aDeploymentCanStillTurnBothBackToTheCompatArm() {
    // What the packaged-IT profile does: its overrides reach the launched process as system
    // properties (ordinal 400), and an empty storage client there reads as unset, above %prod.
    SmallRyeConfig config;
    try {
      config =
          new SmallRyeConfigBuilder()
              .addDefaultInterceptors()
              .withProfile("prod")
              .withSources(new PropertiesConfigSource(shippedProperties(), 250))
              .withSources(new EnvConfigSource(Map.of("QITS_ENVIRONMENT", "dev"), 300))
              .withSources(
                  new PropertiesConfigSource(
                      Map.of(
                          "qits.githost.storage-client", "",
                          "qits.repositories.git.protect-default-branch", "false"),
                      "sys",
                      400))
              .build();
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
    assertFalse(
        config.getOptionalValue("qits.githost.storage-client", String.class).isPresent());
    assertEquals(
        "false", config.getValue("qits.repositories.git.protect-default-branch", String.class));
  }

  @Test
  void devAndTestKeepTheCompatArm() {
    for (String profile : List.of("dev", "test")) {
      SmallRyeConfig config = shipped(profile, Map.of());
      assertFalse(
          config.getOptionalValue("qits.githost.storage-client", String.class).isPresent(),
          profile);
      assertEquals(
          "false",
          config.getValue("qits.repositories.git.protect-default-branch", String.class),
          profile);
    }
  }
}
