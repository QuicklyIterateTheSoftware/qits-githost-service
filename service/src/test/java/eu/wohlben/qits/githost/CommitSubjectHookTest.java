package eu.wohlben.qits.githost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** The grammar, the opt-in file, the bypass option and the exemptions — no Quarkus, no git. */
class CommitSubjectHookTest {

  /** qits-projects' reader, {@code CommitSubjectEntities}, copied: every accepted subject parses. */
  private static final Pattern READER_HEAD = Pattern.compile("^([^()\\s:]*)\\(([^()]+)\\)(!?):");

  private static final Pattern READER_SCOPE =
      Pattern.compile("^([A-Za-z0-9][A-Za-z0-9-]*)-([0-9]{1,18})$");

  @ParameterizedTest
  @ValueSource(
      strings = {
        "feat(qits-1337): x",
        "fix(my-proj-2)!: y",
        "chore/x(qits-1): z",
        "feat(qits-1337): refuse malformed commit subjects\n\nbody line",
        "refactor(other-2-7): split on the last hyphen"
      })
  void accepts(String message) {
    assertTrue(CommitSubjectHook.complies(message), message);
    String first = CommitSubjectHook.firstLine(message).trim();
    Matcher head = READER_HEAD.matcher(first);
    assertTrue(head.find(), "the qits-projects reader must parse " + first);
    assertTrue(READER_SCOPE.matcher(head.group(2).trim()).matches(), first);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "qits-581: foo",
        "free text with no structure",
        "feat(qits): x",
        "feat(qits-1):x",
        "feat(qits-1): ",
        "feat(qits-1):  ",
        "(qits-1): x",
        "",
        "feat(qits-1)",
        "feat (qits-1): x",
        "feat(qits-1234567890123456789): too many digits",
        " feat(qits-1): leading space"
      })
  void refuses(String message) {
    assertFalse(CommitSubjectHook.complies(message), message);
  }

  @Test
  void onlyTheFirstLineCounts() {
    assertFalse(CommitSubjectHook.complies("wip\n\nfeat(qits-1): in the body"));
  }

  @Test
  void theOptInFileMeansEnforceTrueAndNothingElse() {
    assertTrue(CommitSubjectHook.enforceTrue("enforce: true\n"));
    assertTrue(CommitSubjectHook.enforceTrue("# turned on for qits-303\nenforce: true # yes\n"));
    assertTrue(CommitSubjectHook.enforceTrue("other: 1\r\nenforce:true\r\n"));
    assertFalse(CommitSubjectHook.enforceTrue(""));
    assertFalse(CommitSubjectHook.enforceTrue("enforce: false\n"));
    assertFalse(CommitSubjectHook.enforceTrue("enforce: yes\n"));
    assertFalse(CommitSubjectHook.enforceTrue("enforce: \"true\"\n"));
    assertFalse(CommitSubjectHook.enforceTrue("nested:\n  enforce: true\n"));
    assertFalse(CommitSubjectHook.enforceTrue("enforce: true\nenforce: false\n"));
    assertFalse(CommitSubjectHook.enforceTrue("{{{ not yaml"));
  }

  @Test
  void theBypassNeedsAReason() {
    var none = CommitSubjectHook.bypassOption(List.of("qits.no-ci"));
    assertFalse(none.presented());
    assertFalse(none.reasonGiven());

    var bare = CommitSubjectHook.bypassOption(List.of("qits.subject-bypass"));
    assertTrue(bare.presented());
    assertFalse(bare.reasonGiven());

    var blank = CommitSubjectHook.bypassOption(List.of("qits.subject-bypass=   "));
    assertTrue(blank.presented());
    assertFalse(blank.reasonGiven());

    var given = CommitSubjectHook.bypassOption(List.of("qits.subject-bypass=deadlock drill"));
    assertTrue(given.reasonGiven());
    assertEquals("deadlock drill", given.reason());
  }

  @Test
  void exemptionIsByIdentity() {
    CommitSubjectHook hook = new CommitSubjectHook();
    hook.exemptRoles = List.of("qits:system", " qits:ci-run");
    assertTrue(hook.exempt(new CommitSubjectHook.Pusher("svc", Set.of(), true, false)));
    assertTrue(hook.exempt(CommitSubjectHook.Pusher.bootstrapIngress()));
    assertTrue(hook.exempt(new CommitSubjectHook.Pusher("run", Set.of("qits:ci-run"), false, false)));
    assertFalse(hook.exempt(new CommitSubjectHook.Pusher("dyn", Set.of("qits:agent"), false, false)));
    assertFalse(hook.exempt(new CommitSubjectHook.Pusher("alice", Set.of("qits:admin"), false, false)));
    assertFalse(hook.exempt(CommitSubjectHook.Pusher.nobody()));

    // An empty list narrows the roles, never the two identity exemptions.
    hook.exemptRoles = List.of();
    assertTrue(hook.exempt(new CommitSubjectHook.Pusher("svc", Set.of(), true, false)));
    assertTrue(hook.exempt(CommitSubjectHook.Pusher.bootstrapIngress()));
    assertFalse(hook.exempt(new CommitSubjectHook.Pusher("run", Set.of("qits:ci-run"), false, false)));
  }

  @Test
  void theRefusalExplainsItself() {
    var verdict =
        new CommitSubjectHook.Verdict(
            List.of(new CommitSubjectHook.Offender("0123456789abcdef0123456789abcdef01234567", "wip")),
            false);
    String text = String.join("\n", CommitSubjectHook.explanation(verdict, true));
    assertTrue(text.contains("0123456789  wip"), text);
    assertTrue(text.contains("term(<project>-<n>): message"), text);
    assertTrue(text.contains(CommitSubjectHook.EXAMPLE), text);
    assertTrue(text.contains("qits work"), text);
    assertTrue(text.contains("git commit --amend"), text);
    assertTrue(text.contains("git rebase -i"), text);
    assertTrue(text.contains("git push -o qits.subject-bypass=\"<why>\""), text);
    assertTrue(text.contains("recorded"), text);
    assertTrue(text.contains("empty reason"), text);
  }
}
