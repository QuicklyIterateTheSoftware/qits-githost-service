package eu.wohlben.qits.githost;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.quarkus.test.junit.QuarkusTest;
import java.util.List;
import org.eclipse.microprofile.config.ConfigProvider;
import org.junit.jupiter.api.Test;

/**
 * {@code quarkus.oidc.token.audience} must resolve to {@code qits-platform} — a list of one: every
 * token on the platform carries that audience, so nothing else has to be admitted for a caller to
 * reach this service. The door is the audience check; the roles are what decide anything after it.
 *
 * <p>A {@code @QuarkusTest} is what makes this an assertion about the boot, not a value
 * reconstructed by hand: the value is set statically in {@code application.properties} (no
 * environment expansion, so it cannot fail to resolve), but this is the test that would catch it
 * changing or going missing.
 */
@QuarkusTest
public class PlatformAudienceDoorTest {

  @Test
  public void thePlatformAudienceIsTheOnlyOneTheDoorAccepts() {
    String rawValue = ConfigProvider.getConfig().getValue("quarkus.oidc.token.audience", String.class);
    List<String> tokenAudience = List.of(rawValue.split(","));
    assertEquals(List.of("qits-platform"), tokenAudience);
  }
}
