package eu.wohlben.qits.githost.api;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Docker: a file named {@code Dockerfile}, {@code <name>.Dockerfile} or {@code Dockerfile.<name>}.
 * The pins qits-maintenance bumps there are base-image tags, so this format is much narrower than
 * a pom's: a version is only
 *
 * <ul>
 *   <li>the <b>tag</b> of a {@code FROM [--flag=…]… <image>:<tag> [AS <name>]} line — the part after
 *       the image's own {@code :}, so a registry port ({@code host:5000/img:1.2}) is never read as
 *       one; or
 *   <li>the default of a <b>trivial</b> {@code ARG NAME=<version>} — the whole value a version and
 *       nothing else on the line.
 * </ul>
 *
 * <p>The whole tag has to be a version: dotted numbers (one component is enough, {@code 21-jdk} is
 * common here) and an optional {@code -qualifier} such as {@code -alpine} or {@code -jdk-slim},
 * which must agree on both sides. A tag that is a word ({@code latest}, {@code bookworm}) or a
 * variable is not a token. <b>A digest is never a version</b>: a line holding {@code @sha256:…}
 * yields no token at all, so two digests — or a digest moving beside a tag — stay a conflict. Every
 * other line ({@code ENV TOOL_VERSION=…}, a {@code RUN curl …/1.2.3/…}) is plain text.
 */
final class Dockerfile implements PinFormat {

  /** A tag or ARG value that is a version as a whole. */
  private static final String VERSION = "(\\d+(?:\\.\\d+)*)(?:-([A-Za-z0-9][A-Za-z0-9._-]*))?";

  private static final Pattern FROM =
      Pattern.compile(
          "(?i)^\\s*FROM\\s+(?:--[a-z-]+=\\S+\\s+)*\\S+?:(" + VERSION + ")(?:\\s+AS\\s+\\S+)?\\s*$");

  private static final Pattern ARG =
      Pattern.compile("(?i)^\\s*ARG\\s+[a-z_][a-z0-9_]*=(" + VERSION + ")\\s*$");

  @Override
  public String name() {
    return "docker";
  }

  @Override
  public boolean appliesTo(String path) {
    String name = PinVersions.basename(path);
    return name.equals("Dockerfile")
        || (name.endsWith(".Dockerfile") && name.length() > ".Dockerfile".length())
        || (name.startsWith("Dockerfile.") && name.length() > "Dockerfile.".length());
  }

  @Override
  public List<Token> tokens(String line) {
    if (line.contains("@")) {
      // A digest pins content, not a version, and has no order; the line is plain text.
      return List.of();
    }
    for (Pattern pattern : List.of(FROM, ARG)) {
      Matcher matcher = pattern.matcher(line);
      if (matcher.matches()) {
        return List.of(
            new Token(
                matcher.start(1), matcher.end(1), matcher.group(1), matcher.group(2), matcher.group(3)));
      }
    }
    return List.of();
  }
}
