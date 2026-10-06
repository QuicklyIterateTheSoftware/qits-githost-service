package eu.wohlben.qits.githost.api;

import java.util.List;

/**
 * Maven: a file whose basename is {@code pom.xml}. Every standalone dotted version on a line is a
 * token ({@link PinVersions#standalone}) — a property, a {@code <version>}, a plugin's version —
 * ordered by the shared rule, a {@code -SNAPSHOT} or other qualifier having to agree on both sides.
 */
final class MavenPom implements PinFormat {

  @Override
  public String name() {
    return "maven";
  }

  @Override
  public boolean appliesTo(String path) {
    return "pom.xml".equals(PinVersions.basename(path));
  }

  @Override
  public List<Token> tokens(String line) {
    return PinVersions.standalone(line);
  }
}
