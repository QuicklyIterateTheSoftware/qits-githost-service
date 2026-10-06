package eu.wohlben.qits.githost.api;

import java.util.List;

/**
 * npm: a file whose basename is exactly {@code package.json} — never {@code package-lock.json}.
 * Every standalone dotted version is a token. A range operator ({@code ^}, {@code ~}, {@code >=})
 * is NOT part of the token, so it stays in the masked line: {@code ^1.2.3} against {@code ^1.3.0}
 * is decided, {@code ^1.3.0} against {@code ~1.2.9} differs as text and stays a conflict.
 */
final class NpmPackageJson implements PinFormat {

  @Override
  public String name() {
    return "npm";
  }

  @Override
  public boolean appliesTo(String path) {
    return "package.json".equals(PinVersions.basename(path));
  }

  @Override
  public List<Token> tokens(String line) {
    return PinVersions.standalone(line);
  }
}
