package eu.wohlben.qits.githost;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.json.JsonPath;
import jakarta.inject.Inject;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The commit-subject guard over the wire: real git pushes against the served endpoint, under the
 * shipped configuration (no {@code @TestProfile}). See {@link CommitSubjectHook}.
 */
@QuarkusTest
public class GitHostCommitSubjectTest {

  /** A commissioned agent allowed to push main — not exempt. */
  static final List<String> AGENT =
      TestTokenMechanism.token(
          "{\"sub\":\"dyn-x\",\"groups\":[\"qits:agent\"],\"git_refs\":[\"refs/heads/main\"]}");

  /** A CI run, which is what maintenance bumps run as — exempt by role. */
  static final List<String> CI_RUN =
      TestTokenMechanism.token(
          "{\"sub\":\"ci-run-1\",\"groups\":[\"qits:ci-run\"],\"git_refs\":[\"refs/heads/main\"]}");

  @Inject GitRepositoryProvider repositories;

  @TestHTTPResource("/git")
  URL gitBase;

  /** A seeded repository; with {@code enforce}, the opt-in file is on main (pushed by a service). */
  private String origin(boolean enforce) throws Exception {
    String repoId = GitHostFixture.seedOrigin(repositories, gitBase);
    if (enforce) {
      Path clone = GitHostFixture.clone(gitBase, repoId);
      Files.createDirectories(clone.resolve(".config/qits"));
      GitHostFixture.commitFile(
          clone, CommitSubjectHook.CONFIG_PATH, "enforce: true\n", "turn the guard on");
      GitHostFixture.git(clone, "git", "push", "-q", "origin", "main");
    }
    return repoId;
  }

  private String main(String repoId) throws Exception {
    return GitHostFixture.requireRemoteRefSha(gitBase, repoId, "refs/heads/main");
  }

  private static void merge(Path clone, String branch, String message) throws Exception {
    GitHostFixture.git(
        clone, "git", "-c", "user.email=qits@local", "-c", "user.name=qits", "merge", "-q",
        "--no-ff", "-m", message, branch);
  }

  @Test
  public void withoutTheFileAMalformedSubjectPushes() throws Exception {
    String repoId = origin(false);
    Path clone = GitHostFixture.clone(gitBase, repoId);
    GitHostFixture.commitFile(clone, "a.txt", "a\n", "wip");
    GitHostFixture.gitAs(AGENT, clone, "git", "push", "origin", "main");
    assertEquals(GitHostFixture.head(clone), main(repoId));
  }

  @Test
  public void anAgentsMalformedSubjectIsRefusedAndTold() throws Exception {
    String repoId = origin(true);
    String before = main(repoId);
    Path clone = GitHostFixture.clone(gitBase, repoId);
    GitHostFixture.commitFile(clone, "a.txt", "a\n", "qits-581: foo");

    String refusal =
        GitHostFixture.gitExpectingFailureAs(AGENT, clone, "git", "push", "origin", "main");

    assertTrue(refusal.contains(CommitSubjectHook.REJECTION), refusal);
    assertTrue(refusal.contains("remote: For example:"), refusal);
    assertTrue(refusal.contains(CommitSubjectHook.EXAMPLE), refusal);
    assertTrue(refusal.contains("qits-581: foo"), refusal);
    assertTrue(refusal.contains("git push -o qits.subject-bypass=\"<why>\""), refusal);
    assertEquals(before, main(repoId));
  }

  @Test
  public void aComplyingSubjectPushes() throws Exception {
    String repoId = origin(true);
    Path clone = GitHostFixture.clone(gitBase, repoId);
    GitHostFixture.commitFile(clone, "a.txt", "a\n", "feat(qits-303): refuse malformed subjects");
    GitHostFixture.gitAs(AGENT, clone, "git", "push", "origin", "main");
    assertEquals(GitHostFixture.head(clone), main(repoId));
  }

  @Test
  public void aComplyingMultiIdSubjectPushes() throws Exception {
    String repoId = origin(true);
    Path clone = GitHostFixture.clone(gitBase, repoId);
    GitHostFixture.commitFile(clone, "a.txt", "a\n", "chore(qits-1, qits-2): x");
    GitHostFixture.gitAs(AGENT, clone, "git", "push", "origin", "main");
    assertEquals(GitHostFixture.head(clone), main(repoId));
  }

  @Test
  public void theBreakGlassLetsItThroughAndIsRecorded() throws Exception {
    String repoId = origin(true);
    Path clone = GitHostFixture.clone(gitBase, repoId);
    GitHostFixture.commitFile(clone, "a.txt", "a\n", "wip");
    String offending = GitHostFixture.head(clone);

    GitHostFixture.gitAs(
        AGENT, clone, "git", "push", "-o", "qits.subject-bypass=deadlock drill", "origin", "main");
    assertEquals(offending, main(repoId));

    JsonPath body =
        given()
            .header("X-Qits-User", "dyn-reader")
            .header("X-Qits-Roles", "qits:agent")
            .get("/githost/api/repositories/" + repoId + "/commit-subject-bypasses")
            .then()
            .statusCode(200)
            .extract()
            .jsonPath();
    assertEquals(1, body.getList("bypasses").size());
    assertEquals("deadlock drill", body.getString("bypasses[0].reason"));
    assertEquals("dyn-x", body.getString("bypasses[0].pusher"));
    assertEquals(List.of(offending), body.getList("bypasses[0].commits"));
    assertEquals(List.of("refs/heads/main"), body.getList("bypasses[0].refs"));
  }

  @Test
  public void aBypassOnACompliantPushRecordsNothing() throws Exception {
    String repoId = origin(true);
    Path clone = GitHostFixture.clone(gitBase, repoId);
    GitHostFixture.commitFile(clone, "a.txt", "a\n", "fix(qits-303): fine already");
    GitHostFixture.gitAs(
        AGENT, clone, "git", "push", "-o", "qits.subject-bypass=just in case", "origin", "main");

    assertTrue(
        given()
            .header("X-Qits-User", "alice")
            .header("X-Qits-Roles", "qits:admin")
            .get("/githost/api/repositories/" + repoId + "/commit-subject-bypasses")
            .jsonPath()
            .getList("bypasses")
            .isEmpty());
  }

  @Test
  public void anEmptyBypassReasonIsRefused() throws Exception {
    String repoId = origin(true);
    String before = main(repoId);
    Path clone = GitHostFixture.clone(gitBase, repoId);
    GitHostFixture.commitFile(clone, "a.txt", "a\n", "wip");

    String refusal =
        GitHostFixture.gitExpectingFailureAs(
            AGENT, clone, "git", "push", "-o", "qits.subject-bypass=", "origin", "main");

    assertTrue(refusal.contains("empty reason"), refusal);
    assertEquals(before, main(repoId));
  }

  @Test
  public void aFreeTextMergeOfComplyingCommitsIsAccepted() throws Exception {
    String repoId = origin(true);
    Path clone = GitHostFixture.clone(gitBase, repoId);
    GitHostFixture.git(clone, "git", "checkout", "-q", "-b", "feature");
    GitHostFixture.commitFile(clone, "b.txt", "b\n", "feat(qits-303): on the branch");
    GitHostFixture.git(clone, "git", "checkout", "-q", "main");
    GitHostFixture.commitFile(clone, "a.txt", "a\n", "fix(qits-303): on main");
    merge(clone, "feature", "Merge branch 'feature' into main");

    GitHostFixture.gitAs(AGENT, clone, "git", "push", "origin", "main");
    assertEquals(GitHostFixture.head(clone), main(repoId));
  }

  @Test
  public void aMergeBringingInAMalformedCommitIsRefused() throws Exception {
    String repoId = origin(true);
    String before = main(repoId);
    Path clone = GitHostFixture.clone(gitBase, repoId);
    GitHostFixture.git(clone, "git", "checkout", "-q", "-b", "feature");
    GitHostFixture.commitFile(clone, "b.txt", "b\n", "sloppy branch work");
    GitHostFixture.git(clone, "git", "checkout", "-q", "main");
    GitHostFixture.commitFile(clone, "a.txt", "a\n", "fix(qits-303): on main");
    merge(clone, "feature", "feat(qits-303): merge the branch");

    String refusal =
        GitHostFixture.gitExpectingFailureAs(AGENT, clone, "git", "push", "origin", "main");
    assertTrue(refusal.contains("sloppy branch work"), refusal);
    assertFalse(refusal.contains("remote:   " + GitHostFixture.head(clone).substring(0, 10)),
        refusal);
    assertEquals(before, main(repoId));
  }

  @Test
  public void aServiceClientAndACiRunAreExempt() throws Exception {
    String repoId = origin(true);
    Path clone = GitHostFixture.clone(gitBase, repoId);

    GitHostFixture.commitFile(clone, "a.txt", "a\n", "bump(targeted): 3 dependencies");
    GitHostFixture.git(clone, "git", "push", "origin", "main");
    assertEquals(GitHostFixture.head(clone), main(repoId));

    GitHostFixture.commitFile(clone, "b.txt", "b\n", "Release 2026.1001.1");
    GitHostFixture.gitAs(CI_RUN, clone, "git", "push", "origin", "main");
    assertEquals(GitHostFixture.head(clone), main(repoId));
  }
}
