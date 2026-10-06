package eu.wohlben.qits.githost.api;

import eu.wohlben.qits.githost.api.PinFormat.Token;
import eu.wohlben.qits.githost.api.RepositoryRefsResource.ResolvedVersion;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.OptionalInt;
import org.eclipse.jgit.diff.RawText;
import org.eclipse.jgit.diff.Sequence;
import org.eclipse.jgit.merge.MergeChunk;
import org.eclipse.jgit.merge.MergeChunk.ConflictState;
import org.eclipse.jgit.merge.MergeResult;

/**
 * The version-pin merge rule: a text conflict in a manifest that is nothing but two heads bumping
 * the same version pins is decided for the newer versions. This class is the <b>engine</b>; what a
 * pin looks like in each ecosystem is a {@link PinFormat}, and the formats applied are {@link
 * PinFormats#REGISTERED}.
 *
 * <p><b>A merge rule, not a write door.</b> Every byte this writes comes out of the three blobs the
 * merge was already comparing, through JGit's own {@link MergeResult} for the path: the clean
 * chunks exactly as JGit merged them, and each conflicting line as the "ours" line with its version
 * tokens swapped for the newer of the two. Nothing a caller sends can reach the output — a caller
 * only says "apply the rule" ({@code MergeRequest#versionPins}), never what to write.
 *
 * <p><b>Narrow on purpose.</b> A file is decided only if exactly one format reads its path, every
 * conflicting chunk group pairs ours and theirs line for line, every pair is identical once that
 * format's tokens are masked, and every differing token pair is one the format can order. Anything
 * else — a comment changed beside the bump, {@code ^} against {@code ~}, a {@code -SNAPSHOT}
 * against a release, {@code 1.2} against {@code 1.2.0}, a digest, a file no format reads — answers
 * {@code null}, and the path stays a conflict the caller has to fix in its own repository. When in
 * doubt, it is a conflict. Those structural checks live here and not in the formats, so no format
 * can loosen them.
 *
 * <p><b>Bytes, not characters.</b> Lines are read with {@link RawText#writeLine}, which copies the
 * raw bytes without the trailing LF, and are only ever looked at through ISO-8859-1 — a one-to-one
 * byte↔char mapping — so a {@code \r}, a UTF-8 multi-byte sequence or any other encoding survives
 * the round trip byte for byte. The tokens themselves are ASCII.
 */
final class VersionPinRule {

  /** What a token is replaced with when two lines are compared. A line holding one is refused. */
  private static final String MASK = "\u0000";

  /** The decided file: its bytes, and every token pair the decision took the newer side of. */
  record Decision(byte[] content, List<ResolvedVersion> versions) {}

  private VersionPinRule() {}

  /** {@link #decide(String, MergeResult, List)} over the registered formats. */
  static Decision decide(String path, MergeResult<? extends Sequence> result) throws IOException {
    return decide(path, result, PinFormats.REGISTERED);
  }

  /**
   * Decides one path's text conflict from the merger's own result for it, or {@code null} when the
   * rule does not decide it.
   *
   * <p>{@code formats} is the registry to pick from: the one format whose {@link
   * PinFormat#appliesTo} matches {@code path}, or nothing decided.
   *
   * <p>The caller has already established it is a TEXT conflict — a file on both sides, not a
   * gitlink, not a delete/modify. A binary or symlink conflict carries no sequences at all and is
   * refused here as well.
   */
  @SuppressWarnings("unchecked")
  static Decision decide(
      String path, MergeResult<? extends Sequence> result, List<PinFormat> formats)
      throws IOException {
    PinFormat format = PinFormats.forPath(path, formats).orElse(null);
    if (format == null || result == null || !result.containsConflicts()) {
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
                format,
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
   * newer one, or {@code null} when the pair is not a pure version bump in {@code format}'s terms.
   * Adds a {@link ResolvedVersion} per differing pair.
   */
  private static String choose(
      PinFormat format,
      String path,
      int lineNumber,
      String ours,
      String theirs,
      List<ResolvedVersion> versions) {
    if (ours.contains(MASK) || theirs.contains(MASK)) {
      // RawText already calls a NUL binary; refusing it here keeps the mask unambiguous regardless.
      return null;
    }
    List<Token> ourTokens = format.tokens(ours);
    List<Token> theirTokens = format.tokens(theirs);
    if (!wellFormed(ours, ourTokens)
        || !wellFormed(theirs, theirTokens)
        || ourTokens.size() != theirTokens.size()
        || !mask(ours, ourTokens).equals(mask(theirs, theirTokens))) {
      return null;
    }
    StringBuilder chosen = new StringBuilder();
    int from = 0;
    List<ResolvedVersion> found = new ArrayList<>();
    for (int i = 0; i < ourTokens.size(); i++) {
      Token mine = ourTokens.get(i);
      Token other = theirTokens.get(i);
      String pick = mine.text();
      if (!mine.text().equals(other.text())) {
        OptionalInt order = format.compare(mine, other);
        if (order.isEmpty() || order.getAsInt() == 0) {
          // Unorderable: different qualifiers, one value spelled two ways, or a pair the format
          // will not vouch for. There is no newer side.
          return null;
        }
        pick = order.getAsInt() > 0 ? mine.text() : other.text();
        found.add(new ResolvedVersion(path, lineNumber, mine.text(), other.text(), pick));
      }
      chosen.append(ours, from, mine.start()).append(pick);
      from = mine.end();
    }
    chosen.append(ours, from, ours.length());
    versions.addAll(found);
    return chosen.toString();
  }

  /**
   * Whether a format's tokens are what the contract promises: in order, not overlapping, inside the
   * line, and each {@code text} the span it claims. A format that breaks it leaves the conflict
   * standing rather than steering a rewrite.
   */
  private static boolean wellFormed(String line, List<Token> tokens) {
    int from = 0;
    for (Token token : tokens) {
      if (token.start() < from
          || token.end() <= token.start()
          || token.end() > line.length()
          || !line.substring(token.start(), token.end()).equals(token.text())
          || token.value() == null) {
        return false;
      }
      from = token.end();
    }
    return true;
  }

  /** The line with each (well-formed) token replaced by {@link #MASK}. */
  private static String mask(String line, List<Token> tokens) {
    StringBuilder masked = new StringBuilder();
    int from = 0;
    for (Token token : tokens) {
      masked.append(line, from, token.start()).append(MASK);
      from = token.end();
    }
    return masked.append(line, from, line.length()).toString();
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
