package eu.wohlben.qits.githost.api;

import eu.wohlben.qits.githost.api.RepositoryRefsResource.ResolvedVersion;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.eclipse.jgit.diff.RawText;
import org.eclipse.jgit.diff.Sequence;
import org.eclipse.jgit.merge.MergeChunk;
import org.eclipse.jgit.merge.MergeChunk.ConflictState;
import org.eclipse.jgit.merge.MergeResult;

/**
 * The version-pin merge rule: a text conflict in a manifest that is nothing but two heads bumping
 * the same version token is decided for the newer version.
 *
 * <p><b>A merge rule, not a write door.</b> Every byte this writes comes out of the three blobs the
 * merge was already comparing, through JGit's own {@link MergeResult} for the path: the clean
 * chunks exactly as JGit merged them, and each conflicting line as the "ours" line with its version
 * tokens swapped for the newer of the two. Nothing a caller sends can reach the output — a caller
 * only says "apply the rule" ({@code MergeRequest#versionPins}), never what to write.
 *
 * <p><b>Narrow on purpose.</b> A file is decided only if every conflicting chunk group pairs ours
 * and theirs line for line, every pair is identical once version tokens are masked, and every
 * differing token pair is <em>orderable</em>: the same qualifier (or none on both), numerically
 * different. Anything else — a comment changed beside the bump, {@code ^} against {@code ~}, a
 * {@code -SNAPSHOT} against a release, {@code 1.2} against {@code 1.2.0}, a file that is not a
 * {@code pom.xml} or {@code package.json} — answers {@code null}, and the path stays a conflict the
 * caller has to fix in its own repository. When in doubt, it is a conflict.
 *
 * <p><b>Bytes, not characters.</b> Lines are read with {@link RawText#writeLine}, which copies the
 * raw bytes without the trailing LF, and are only ever looked at through ISO-8859-1 — a one-to-one
 * byte↔char mapping — so a {@code \r}, a UTF-8 multi-byte sequence or any other encoding survives
 * the round trip byte for byte. The tokens themselves are ASCII.
 */
final class VersionPinRule {

  /** The only basenames the rule applies to. {@code package-lock.json} is deliberately not one. */
  private static final Set<String> MANIFESTS = Set.of("pom.xml", "package.json");

  /**
   * A version token: dotted numbers with an optional {@code -QUALIFIER}, standing alone — not glued
   * to a letter, digit, underscore, dot or dash on either side. So {@code jdk1.8}, {@code
   * foo-1.2.jar} and {@code 1.2.3-rc.1} are plain text and never a token; that only ever leaves a
   * conflict standing, which is the safe way to be wrong.
   */
  private static final Pattern TOKEN =
      Pattern.compile(
          "(?<![A-Za-z0-9_.-])(\\d+(?:\\.\\d+)+)(?:-([A-Za-z][A-Za-z0-9]*))?(?![A-Za-z0-9_.-])");

  /** What a token is replaced with when two lines are compared. A line holding one is refused. */
  private static final String MASK = "\u0000";

  /** The decided file: its bytes, and every token pair the decision took the newer side of. */
  record Decision(byte[] content, List<ResolvedVersion> versions) {}

  private VersionPinRule() {}

  /** Whether the rule applies to this path at all: its basename is a manifest it knows. */
  static boolean applies(String path) {
    return MANIFESTS.contains(path.substring(path.lastIndexOf('/') + 1));
  }

  /**
   * Decides one path's text conflict from the merger's own result for it, or {@code null} when the
   * rule does not decide it.
   *
   * <p>The caller has already established it is a TEXT conflict — a file on both sides, not a
   * gitlink, not a delete/modify. A binary or symlink conflict carries no sequences at all and is
   * refused here as well.
   */
  @SuppressWarnings("unchecked")
  static Decision decide(String path, MergeResult<? extends Sequence> result) throws IOException {
    if (!applies(path) || result == null || !result.containsConflicts()) {
      return null;
    }
    List<? extends Sequence> sequences = result.getSequences();
    if (sequences.size() != 3 || !sequences.stream().allMatch(RawText.class::isInstance)) {
      return null;
    }
    MergeResult<RawText> text = (MergeResult<RawText>) result;
    RawText ours = text.getSequences().get(1);
    RawText theirs = text.getSequences().get(2);

    ByteArrayOutputStream out = new ByteArrayOutputStream();
    List<ResolvedVersion> versions = new ArrayList<>();
    int written = 0;
    // Where the last written line came from, so the trailing newline is the source's own.
    RawText lastSequence = null;
    int lastIndex = -1;

    Iterator<MergeChunk> chunks = text.iterator();
    MergeChunk pending = null;
    while (pending != null || chunks.hasNext()) {
      MergeChunk chunk = pending != null ? pending : chunks.next();
      pending = null;
      if (chunk.getConflictState() == ConflictState.NO_CONFLICT) {
        RawText sequence = text.getSequences().get(chunk.getSequenceIndex());
        for (int i = chunk.getBegin(); i < chunk.getEnd(); i++) {
          out.write(line(sequence, i));
          out.write('\n');
          written++;
          lastSequence = sequence;
          lastIndex = i;
        }
        continue;
      }
      if (chunk.getConflictState() != ConflictState.FIRST_CONFLICTING_RANGE
          || chunk.getSequenceIndex() != 1) {
        return null;
      }
      // A group is ours, an optional base (diff3 style), then theirs.
      MergeChunk ourChunk = chunk;
      MergeChunk theirChunk = null;
      while (chunks.hasNext()) {
        MergeChunk next = chunks.next();
        if (next.getConflictState() == ConflictState.BASE_CONFLICTING_RANGE) {
          continue;
        }
        if (next.getConflictState() == ConflictState.NEXT_CONFLICTING_RANGE
            && next.getSequenceIndex() == 2) {
          theirChunk = next;
        } else {
          pending = next;
        }
        break;
      }
      if (theirChunk == null) {
        return null;
      }
      int count = ourChunk.getEnd() - ourChunk.getBegin();
      if (count == 0 || count != theirChunk.getEnd() - theirChunk.getBegin()) {
        return null;
      }
      // The lines compare without their LF, so a group reaching the end of a file where only one
      // side has a final newline would hide that difference; it is a difference, so refuse it.
      boolean oursAtEnd = ourChunk.getEnd() == ours.size();
      boolean theirsAtEnd = theirChunk.getEnd() == theirs.size();
      if ((oursAtEnd || theirsAtEnd)
          && (oursAtEnd != theirsAtEnd
              || ours.isMissingNewlineAtEnd() != theirs.isMissingNewlineAtEnd())) {
        return null;
      }
      for (int k = 0; k < count; k++) {
        int lineNumber = written + 1;
        String chosen =
            choose(
                path,
                lineNumber,
                latin1(line(ours, ourChunk.getBegin() + k)),
                latin1(line(theirs, theirChunk.getBegin() + k)),
                versions);
        if (chosen == null) {
          return null;
        }
        out.write(chosen.getBytes(StandardCharsets.ISO_8859_1));
        out.write('\n');
        written++;
        lastSequence = ours;
        lastIndex = ourChunk.getBegin() + k;
      }
    }
    if (versions.isEmpty()) {
      return null;
    }
    byte[] content = out.toByteArray();
    if (lastSequence != null
        && lastIndex == lastSequence.size() - 1
        && lastSequence.isMissingNewlineAtEnd()) {
      content = Arrays.copyOf(content, content.length - 1);
    }
    return new Decision(content, versions);
  }

  /**
   * One conflicting line pair, decided: the ours line with every differing token replaced by the
   * newer one, or {@code null} when the pair is not a pure version bump. Adds a {@link
   * ResolvedVersion} per differing pair.
   */
  private static String choose(
      String path, int lineNumber, String ours, String theirs, List<ResolvedVersion> versions) {
    if (ours.contains(MASK) || theirs.contains(MASK)) {
      // RawText already calls a NUL binary; refusing it here keeps the mask unambiguous regardless.
      return null;
    }
    List<MatchResult> ourTokens = tokens(ours);
    List<MatchResult> theirTokens = tokens(theirs);
    if (ourTokens.size() != theirTokens.size()
        || !mask(ours, ourTokens).equals(mask(theirs, theirTokens))) {
      return null;
    }
    StringBuilder chosen = new StringBuilder();
    int from = 0;
    List<ResolvedVersion> found = new ArrayList<>();
    for (int i = 0; i < ourTokens.size(); i++) {
      MatchResult mine = ourTokens.get(i);
      MatchResult other = theirTokens.get(i);
      String pick = mine.text();
      if (!mine.text().equals(other.text())) {
        if (!Objects.equals(mine.qualifier(), other.qualifier())) {
          return null;
        }
        int order = compare(mine.number(), other.number());
        if (order == 0) {
          // Equal values spelled differently (1.2 vs 1.2.0, 01 vs 1): there is no newer one.
          return null;
        }
        pick = order > 0 ? mine.text() : other.text();
        found.add(new ResolvedVersion(path, lineNumber, mine.text(), other.text(), pick));
      }
      chosen.append(ours, from, mine.start()).append(pick);
      from = mine.end();
    }
    chosen.append(ours, from, ours.length());
    versions.addAll(found);
    return chosen.toString();
  }

  /** One token on a line: where it is, what it says, its numeric part and its qualifier. */
  private record MatchResult(int start, int end, String text, String number, String qualifier) {}

  private static List<MatchResult> tokens(String line) {
    List<MatchResult> tokens = new ArrayList<>();
    Matcher matcher = TOKEN.matcher(line);
    while (matcher.find()) {
      tokens.add(
          new MatchResult(
              matcher.start(), matcher.end(), matcher.group(), matcher.group(1), matcher.group(2)));
    }
    return tokens;
  }

  private static String mask(String line, List<MatchResult> tokens) {
    StringBuilder masked = new StringBuilder();
    int from = 0;
    for (MatchResult token : tokens) {
      masked.append(line, from, token.start()).append(MASK);
      from = token.end();
    }
    return masked.append(line, from, line.length()).toString();
  }

  /**
   * Component by component, numerically, the shorter padded with zeros. Arbitrary length: a
   * component is compared without its leading zeros, by length and then by digits, so no parse can
   * overflow.
   */
  static int compare(String left, String right) {
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

  private static byte[] line(RawText sequence, int index) throws IOException {
    ByteArrayOutputStream line = new ByteArrayOutputStream();
    sequence.writeLine(line, index);
    return line.toByteArray();
  }

  private static String latin1(byte[] bytes) {
    return new String(bytes, StandardCharsets.ISO_8859_1);
  }
}
