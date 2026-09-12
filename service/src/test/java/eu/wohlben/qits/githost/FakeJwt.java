package eu.wohlben.qits.githost;

import java.util.Map;
import java.util.Set;
import org.eclipse.microprofile.jwt.JsonWebToken;

/** A JWT principal with the claims a test gives it. No signature, no parsing. */
public record FakeJwt(String name, Map<String, Object> claims) implements JsonWebToken {

  @Override
  public String getName() {
    return name;
  }

  @Override
  public Set<String> getClaimNames() {
    return claims.keySet();
  }

  @Override
  @SuppressWarnings("unchecked")
  public <T> T getClaim(String claimName) {
    return (T) claims.get(claimName);
  }
}
