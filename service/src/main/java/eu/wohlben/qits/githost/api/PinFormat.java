package eu.wohlben.qits.githost.api;

import java.util.List;
import java.util.OptionalInt;

/**
 * One ecosystem's way of spelling a version pin — the extension point of the version-pin merge
 * rule ({@link VersionPinRule}).
 *
 * <p>The engine owns everything that makes the rule safe: walking JGit's merge chunks, insisting
 * conflicting groups pair line for line, comparing lines with their tokens masked, all or none per
 * file, the file's own bytes and newlines, and the {@code ResolvedVersion} report. A format owns
 * only the three questions that differ between ecosystems: <b>which files</b>, <b>which spans of a
 * line are versions</b>, and <b>which of two versions is newer</b>. It never sees a file, never
 * writes a byte, and cannot widen what the rule decides beyond "the same line with a newer pin".
 *
 * <h2>Adding an ecosystem</h2>
 *
 * <p>One class implementing this, plus one entry in {@link PinFormats#REGISTERED}. Nothing in the
 * engine changes. Two worked sketches, deliberately not shipped yet:
 *
 * <ul>
 *   <li><b>Rust</b> — {@code appliesTo} the basename {@code Cargo.toml} (never {@code Cargo.lock});
 *       {@code tokens} the quoted requirement of a dependency or {@code version =} key, where a
 *       leading {@code ^}, {@code ~} or {@code =} stays OUTSIDE the token so that, as for npm, an
 *       operator that differs between the sides is a masked-line difference and the file stays
 *       conflicted.
 *   <li><b>Go</b> — {@code appliesTo} the basename {@code go.mod} (never {@code go.sum}); {@code
 *       tokens} the module versions of {@code require} lines. Go spells a version {@code v1.2.3},
 *       and the {@code v} belongs to <em>this format's tokenizer</em>: the token's {@code text} is
 *       {@code v1.2.3} (what is written back), its {@code value} is {@code 1.2.3} (what is
 *       compared). A pseudo-version ({@code v0.0.0-20260101120000-abcdef123456}) carries a
 *       qualifier that never matches another and so stays a conflict, which is the right answer.
 * </ul>
 *
 * <p>Rules every format keeps:
 *
 * <ul>
 *   <li><b>A lockfile never matches.</b> A lockfile is generated from the manifest and carries
 *       integrity hashes the rule cannot recompute; deciding its pins would write a file that lies.
 *   <li><b>When in doubt, no token.</b> A span that is not a token is compared as plain text, so a
 *       too-narrow tokenizer only leaves a conflict standing. A too-wide one decides something it
 *       should not have.
 *   <li><b>At most one format per path.</b> If two registered formats claim one path the engine
 *       decides nothing for it, rather than pick one by registration order.
 * </ul>
 */
interface PinFormat {

  /** A short name for logs and tests, e.g. {@code maven}. */
  String name();

  /** Whether this format reads the file at {@code path} (repository-relative). Never a lockfile. */
  boolean appliesTo(String path);

  /**
   * The version tokens on one line, in order of position and not overlapping. The line is given
   * without its LF, decoded byte-for-char (ISO-8859-1), so positions are byte offsets and a
   * {@code \r} is still on it. An empty list means the whole line is plain text.
   */
  List<Token> tokens(String line);

  /**
   * Which of two tokens that differ is newer: positive when {@code ours} is, negative when {@code
   * theirs} is, and empty when the pair is <b>unorderable</b> — different qualifiers, equal values
   * spelled differently, anything this format will not vouch for. Empty leaves the file conflicted.
   *
   * <p>The default is the dotted-number rule most ecosystems share ({@link PinVersions#order}).
   */
  default OptionalInt compare(Token ours, Token theirs) {
    return PinVersions.order(ours, theirs);
  }

  /**
   * One version on a line: {@code [start, end)} is the span replaced when the other side wins,
   * {@code text} that span verbatim, {@code value} the dotted number that is compared (without any
   * prefix the ecosystem spells, like Go's {@code v}), and {@code qualifier} the suffix that has to
   * agree on both sides, or {@code null} for none.
   */
  record Token(int start, int end, String text, String value, String qualifier) {}
}
