package eu.wohlben.qits.githost.api;

import eu.wohlben.qits.githost.api.PinFormat.Token;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The version vocabulary the {@link PinFormat}s share: a standalone dotted-number scanner and the
 * calver/semver ordering. Helpers, not engine logic — a format uses what fits and spells the rest
 * itself.
 */
final class PinVersions {

  /**
   * A standalone version: dotted numbers with an optional {@code -QUALIFIER}, not glued to a
   * letter, digit, underscore, dot or dash on either side. So {@code jdk1.8}, {@code foo-1.2.jar}
   * and {@code 1.2.3-rc.1} are plain text and never a token; that only ever leaves a conflict
   * standing, which is the safe way to be wrong.
   */
  private static final Pattern STANDALONE =
      Pattern.compile(
          "(?<![A-Za-z0-9_.-])(\\d+(?:\\.\\d+)+)(?:-([A-Za-z][A-Za-z0-9]*))?(?![A-Za-z0-9_.-])");

  private PinVersions() {}

  /** Every standalone version on the line — the whole tokenizer of a pom or a package.json. */
  static List<Token> standalone(String line) {
    List<Token> tokens = new ArrayList<>();
    Matcher matcher = STANDALONE.matcher(line);
    while (matcher.find()) {
      tokens.add(
          new Token(
              matcher.start(), matcher.end(), matcher.group(), matcher.group(1), matcher.group(2)));
    }
    return tokens;
  }

  /** The basename of a repository-relative path. */
  static String basename(String path) {
    return path.substring(path.lastIndexOf('/') + 1);
  }

  /**
   * The shared ordering: the same qualifier (or none on both), then the values compared component
   * by component as numbers, the shorter padded with zeros. Empty when the qualifiers differ or the
   * values are equal however they are spelled ({@code 1.2} against {@code 1.2.0}, {@code 01}
   * against {@code 1}) — there is then no newer side.
   */
  static OptionalInt order(Token ours, Token theirs) {
    if (!Objects.equals(ours.qualifier(), theirs.qualifier())) {
      return OptionalInt.empty();
    }
    int order = compareNumbers(ours.value(), theirs.value());
    return order == 0 ? OptionalInt.empty() : OptionalInt.of(order);
  }

  /**
   * Component by component, numerically, the shorter padded with zeros. Arbitrary length: a
   * component is compared without its leading zeros, by length and then by digits, so no parse can
   * overflow — {@code 2026.1006.x} beats {@code 2026.919.x}.
   */
  static int compareNumbers(String left, String right) {
    String[] a = left.split("\\.");
    String[] b = right.split("\\.");
    for (int i = 0; i < Math.max(a.length, b.length); i++) {
      String x = i < a.length ? stripZeros(a[i]) : "";
      String y = i < b.length ? stripZeros(b[i]) : "";
      int order = x.length() != y.length() ? Integer.compare(x.length(), y.length()) : x.compareTo(y);
      if (order != 0) {
        return order;
      }
    }
    return 0;
  }

  private static String stripZeros(String digits) {
    int i = 0;
    while (i < digits.length() && digits.charAt(i) == '0') {
      i++;
    }
    return digits.substring(i);
  }
}
