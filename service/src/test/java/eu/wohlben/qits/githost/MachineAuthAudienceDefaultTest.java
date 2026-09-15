package eu.wohlben.qits.githost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.test.junit.QuarkusTest;
import java.util.List;
import java.util.Optional;
import org.eclipse.microprofile.config.ConfigProvider;
import org.junit.jupiter.api.Test;

/**
 * {@code qits.auth.machine.audience} must resolve to {@code qits-platform} when {@code
 * QITS_AUTH_MACHINE_AUDIENCE} is unset — the receive-only default from the service-client-identity
 * plan (contract C4). Before this default, a deployment that dropped that extras entry stopped the
 * service from booting at all: the expression had no fallback.
 *
 * <p>Nothing in the build sets {@code QITS_AUTH_MACHINE_AUDIENCE}, so this test runs against the
 * real shipped default. A {@code @QuarkusTest} is what makes that true: it reads the merged
 * configuration the application actually boots with, not a value reconstructed by hand.
 */
@QuarkusTest
public class MachineAuthAudienceDefaultTest {

  @Test
  public void unsetVariableResolvesToThePlatformAudience() {
    Optional<String> configuredVariable =
        ConfigProvider.getConfig().getOptionalValue("QITS_AUTH_MACHINE_AUDIENCE", String.class);
    assertTrue(
        configuredVariable.isEmpty(),
        "this test proves the DEFAULT; set QITS_AUTH_MACHINE_AUDIENCE only where that is the point");

    String resolved =
        ConfigProvider.getConfig().getValue("qits.auth.machine.audience", String.class);
    assertEquals("qits-platform", resolved);
  }

  @Test
  public void thePlatformAudienceIsTheOnlyOneTheDoorAccepts() {
    // quarkus.oidc.token.audience = qits-platform, a list of one: every token on the platform
    // carries that audience, so nothing else has to be admitted for a caller to reach this
    // service. The door is the audience check; the roles are what decide anything after it.
    String rawValue = ConfigProvider.getConfig().getValue("quarkus.oidc.token.audience", String.class);
    List<String> tokenAudience = List.of(rawValue.split(","));
    assertEquals(List.of("qits-platform"), tokenAudience);
  }
}
