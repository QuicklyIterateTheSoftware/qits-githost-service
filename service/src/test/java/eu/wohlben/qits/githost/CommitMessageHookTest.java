package eu.wohlben.qits.githost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/** The variants, their classification and the refusal text — no Quarkus, no git. */
class CommitMessageHookTest {

  /** qits-projects' reader, {@code CommitSubjectEntities}, copied: every accepted subject parses. */
  private static final Pattern READER_HEAD = Pattern.compile("^([^()\\s:]*)\\(([^()]+)\\)(!?):");

  private static final Pattern READER_SCOPE =
      Pattern.compile("^([A-Za-z0-9][A-Za-z0-9-]*)-([0-9]{1,18})$");

  /**
   * {@code commit-message-vectors.tsv}, shared verbatim with qits-projects-service: expected
   * variant (or {@code NONE}), parent count, message with {@code \n} written for a newline.
   */
  static Stream<Arguments> vectors() throws Exception {
    List<Arguments> rows = new ArrayList<>();
    try (InputStream in = CommitMessageHookTest.class.getResourceAsStream("/commit-message-vectors.tsv")) {
      assertNotNull(in, "commit-message-vectors.tsv is on the test classpath");
      BufferedReader reader =
          new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
      String line;
      while ((line = reader.readLine()) != null) {
        if (line.isEmpty() || line.startsWith("#")) {
          continue;
        }
        String[] columns = line.split("\t", 3);
        assertEquals(3, columns.length, "three columns: " + line);
        rows.add(
            Arguments.of(
                columns[0], Integer.parseInt(columns[1]), columns[2].replace("\\n", "\n")));
      }
    }
    assertFalse(rows.isEmpty());
    return rows.stream();
  }

  @ParameterizedTest(name = "[{index}] {0} <- {1} parent(s): {2}")
  @MethodSource("vectors")
  void classifiesEveryVector(String expected, int parents, String message) {
    Optional<CommitMessageVariants> variant = CommitMessageVariants.classify(message, parents);
    assertEquals(expected, variant.map(Enum::name).orElse("NONE"), message);
    assertEquals(variant.isPresent(), CommitMessageHook.complies(message, parents));
  }

  @Test
  void theVectorsCoverEveryVariant() throws Exception {
    List<String> covered = vectors().map(a -> (String) a.get()[0]).distinct().toList();
    for (CommitMessageVariants variant : CommitMessageVariants.values()) {
      assertTrue(covered.contains(variant.name()), variant.name());
    }
  }

  @ParameterizedTest
  @EnumSource(CommitMessageVariants.class)
  void everyExampleClassifiesAsItsOwnVariant(CommitMessageVariants variant) {
    int parents = variant == CommitMessageVariants.MERGE ? 2 : 1;
    assertEquals(
        Optional.of(variant), CommitMessageVariants.classify(variant.example(), parents));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "feat(qits-1337): x",
        "fix(my-proj-2)!: y",
        "chore/x(qits-1): z",
        "refactor(other-2-7): split on the last hyphen",
        "chore(qits-731, qits-882): bump(dependencies): 2 dependencies",
        "chore(qits-1,qits-2): x",
        "chore(qits-1, other-project-12, qits-3): x",
        "chore(qits-1, qits-1): x"
      })
  void everySubjectTheReaderCanParse(String message) {
    assertEquals(
        Optional.of(CommitMessageVariants.SUBJECT), CommitMessageVariants.classify(message, 1));
    Matcher head = READER_HEAD.matcher(message);
    assertTrue(head.find(), "the qits-projects reader must parse " + message);
    for (String part : head.group(2).split(",", -1)) {
      assertTrue(READER_SCOPE.matcher(part.strip()).matches(), message);
    }
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "qits-581: foo",
        "free text with no structure",
        "feat(qits-1)",
        "feat (qits-1): x",
        " feat(qits-1): leading space",
        "chore(qits-1 , qits-2): x",
        "chore(, qits-1): x",
        "chore(): x",
        "chore(qits-1; qits-2): x",
        "wip\n\nfeat(qits-1): only in the body"
      })
  void refuses(String message) {
    assertEquals(Optional.empty(), CommitMessageVariants.classify(message, 1), message);
    assertEquals(Optional.empty(), CommitMessageVariants.classify(message, 0), message);
  }

  @Test
  void theRefusalExplainsItselfFromTheVariants() {
    List<CommitMessageHook.Offender> offenders = new ArrayList<>();
    for (int i = 0; i < 12; i++) {
      offenders.add(
          new CommitMessageHook.Offender(
              String.format("%010d", i) + "0123456789abcdef0123456789", "wip " + i));
    }
    String text =
        String.join("\n", CommitMessageHook.explanation(new CommitMessageHook.Verdict(offenders, false)));

    assertTrue(text.contains("0000000000  wip 0"), text);
    assertTrue(text.contains("0000000009  wip 9"), text);
    assertFalse(text.contains("wip 10"), text);
    assertTrue(text.contains("... and 2 more"), text);
    for (CommitMessageVariants variant : CommitMessageVariants.values()) {
      assertTrue(text.contains(variant.name() + ": " + variant.form()), text);
      assertTrue(text.contains(variant.example()), text);
    }
    assertTrue(text.contains("qualified id of the ticket, epic or task"), text);
    assertTrue(text.contains("dispatch prompt"), text);
    assertTrue(text.contains("qits work"), text);
    assertTrue(text.contains("git commit --amend"), text);
    assertTrue(text.contains("git rebase -i"), text);
    assertFalse(text.contains("bypass"), text);
    assertFalse(text.contains("commit-subjects.yml"), text);
    assertFalse(text.contains("enforce"), text);
  }

  @Test
  void theCapRefusalSaysWhy() {
    String text =
        String.join(
            "\n", CommitMessageHook.explanation(new CommitMessageHook.Verdict(List.of(), true)));
    assertTrue(text.contains("more than " + CommitMessageHook.WALK_CAP), text);
    assertFalse(text.contains("bypass"), text);
    assertFalse(text.contains("break-glass"), text);
  }
}
