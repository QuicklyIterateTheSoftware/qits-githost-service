package eu.wohlben.qits.githost;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The commit-message rule over the wire: real git pushes against the served endpoint, and the
 * REST commit primitive, under the shipped configuration (no {@code @TestProfile}). See {@link
 * CommitMessageHook} and {@link CommitMessageVariants}.
 */
@QuarkusTest
public class GitHostCommitMessageTest {

  /** A commissioned agent allowed to push main. */
  static final List<String> AGENT =
      TestTokenMechanism.token(
          "{\"sub\":\"dyn-x\",\"groups\":[\"qits:agent\"],\"git_refs\":[\"refs/heads/main\"]}");

  /** A CI run, which is what maintenance bumps run as. */
  static final List<String> CI_RUN =
      TestTokenMechanism.token(
          "{\"sub\":\"ci-run-1\",\"groups\":[\"qits:ci-run\"],\"git_refs\":[\"refs/heads/main\"]}");

  /** A platform service client holding {@code qits:system} — no scope, every ref. */
  static final List<String> SERVICE = TestTokenMechanism.token(TestTokenMechanism.PLATFORM_SERVICE_CLAIMS);

  static final String API = "/githost/api/repositories/";

  @Inject GitRepositoryProvider repositories;

  @TestHTTPResource("/git")
  URL gitBase;

  private String main(String repoId) throws Exception {
    return GitHostFixture.requireRemoteRefSha(gitBase, repoId, "refs/heads/main");
  }

  private static void merge(Path clone, String branch, String message) throws Exception {
    GitHostFixture.git(
        clone, "git", "-c", "user.email=qits@local", "-c", "user.name=qits", "merge", "-q",
        "--no-ff", "-m", message, branch);
  }

  private static void assertExplains(String refusal, String offendingSubject) {
    assertTrue(refusal.contains(CommitMessageHook.REJECTION), refusal);
    assertTrue(refusal.contains(offendingSubject), refusal);
    for (CommitMessageVariants variant : CommitMessageVariants.values()) {
      assertTrue(refusal.contains(variant.example()), refusal);
    }
    assertFalse(refusal.contains("bypass"), refusal);
    assertFalse(refusal.contains("commit-subjects.yml"), refusal);
  }

  // --- the store ---------------------------------------------------------------------------------

  @Test
  public void anUnbornHeadIsNoRefOnTheShippedStore() throws Exception {
    String repoId = GitHostFixture.emptyOrigin(repositories);
    try (Repository repo = repositories.open(repoId)) {
      // HEAD is there as a symref to refs/heads/main, which does not exist yet.
      Ref head = repo.exactRef("HEAD");
      if (head != null) {
        assertTrue(head.isSymbolic(), head.toString());
        assertNull(head.getObjectId(), head.toString());
      }
      assertFalse(CommitMessageHook.hasRefs(repo));
    }

    Path local = GitHostFixture.localRepo();
    GitHostFixture.git(local, "git", "push", "-q", gitBase + "/" + repoId, "main");
    try (Repository repo = repositories.open(repoId)) {
      assertTrue(CommitMessageHook.hasRefs(repo));
    }
  }

  // --- every variant is accepted -----------------------------------------------------------------

  @ParameterizedTest
  @ValueSource(
      strings = {
        "feat(qits-303): refuse malformed messages",
        "chore(qits-9, qits-10): bump(targeted): 3 dependencies",
        "bump(targeted): 3 dependencies",
        "release(2026.1001.1): stamp the version",
        "chore(platform): initialize the repository template skeleton"
      })
  public void anAgentPushesEveryFirstLineVariant(String message) throws Exception {
    String repoId = GitHostFixture.seedOrigin(repositories, gitBase);
    Path clone = GitHostFixture.clone(gitBase, repoId);
    GitHostFixture.commitFile(clone, "a.txt", "a\n", message);
    GitHostFixture.gitAs(AGENT, clone, "git", "push", "origin", "main");
    assertEquals(GitHostFixture.head(clone), main(repoId));
  }

  @Test
  public void aFreeTextMergeOfCompliantCommitsIsAccepted() throws Exception {
    String repoId = GitHostFixture.seedOrigin(repositories, gitBase);
    Path clone = GitHostFixture.clone(gitBase, repoId);
    GitHostFixture.git(clone, "git", "checkout", "-q", "-b", "feature");
    GitHostFixture.commitFile(clone, "b.txt", "b\n", "feat(qits-303): on the branch");
    GitHostFixture.git(clone, "git", "checkout", "-q", "main");
    GitHostFixture.commitFile(clone, "a.txt", "a\n", "fix(qits-303): on main");
    merge(clone, "feature", "Merge branch 'feature' into main");

    GitHostFixture.gitAs(AGENT, clone, "git", "push", "origin", "main");
    assertEquals(GitHostFixture.head(clone), main(repoId));
  }

  // --- a malformed message is refused, whoever pushes it -----------------------------------------

  @Test
  public void anAgentsMalformedMessageIsRefusedAndTold() throws Exception {
    assertRefusedFor(AGENT);
  }

  @Test
  public void aServiceClientsMalformedMessageIsRefused() throws Exception {
    assertRefusedFor(SERVICE);
  }

  @Test
  public void theSuitesOwnServiceClientIsRefusedToo() throws Exception {
    assertRefusedFor(TestTokenMechanism.SERVICE_CLIENT);
  }

  @Test
  public void aCiRunsMalformedMessageIsRefused() throws Exception {
    assertRefusedFor(CI_RUN);
  }

  private void assertRefusedFor(List<String> pusher) throws Exception {
    String repoId = GitHostFixture.seedOrigin(repositories, gitBase);
    String before = main(repoId);
    Path clone = GitHostFixture.clone(gitBase, repoId);
    GitHostFixture.commitFile(clone, "a.txt", "a\n", "Release 2026.1001.1");

    String refusal =
        GitHostFixture.gitExpectingFailureAs(pusher, clone, "git", "push", "origin", "main");

    assertExplains(refusal, "Release 2026.1001.1");
    assertEquals(before, main(repoId));
  }

  @Test
  public void aTagOnAMalformedCommitIsRefused() throws Exception {
    String repoId = GitHostFixture.seedOrigin(repositories, gitBase);
    Path clone = GitHostFixture.clone(gitBase, repoId);
    GitHostFixture.commitFile(clone, "a.txt", "a\n", "wip");
    GitHostFixture.git(
        clone, "git", "-c", "user.email=qits@local", "-c", "user.name=qits", "tag", "-a", "-m",
        "free text is fine on a tag", "v1");

    String refusal =
        GitHostFixture.gitExpectingFailure(clone, "git", "push", "origin", "v1");
    assertExplains(refusal, "wip");
    assertNull(GitHostFixture.remoteRefSha(gitBase, repoId, "refs/tags/v1"));
  }

  @Test
  public void aFreeTextMergeBringingInAMalformedCommitIsRefused() throws Exception {
    String repoId = GitHostFixture.seedOrigin(repositories, gitBase);
    String before = main(repoId);
    Path clone = GitHostFixture.clone(gitBase, repoId);
    GitHostFixture.git(clone, "git", "checkout", "-q", "-b", "feature");
    GitHostFixture.commitFile(clone, "b.txt", "b\n", "sloppy branch work");
    GitHostFixture.git(clone, "git", "checkout", "-q", "main");
    GitHostFixture.commitFile(clone, "a.txt", "a\n", "fix(qits-303): on main");
    merge(clone, "feature", "Merge branch 'feature' into main");

    String refusal =
        GitHostFixture.gitExpectingFailureAs(AGENT, clone, "git", "push", "origin", "main");
    assertExplains(refusal, "sloppy branch work");
    // The merge itself is MERGE and is not listed.
    assertFalse(
        refusal.contains("remote:   " + GitHostFixture.head(clone).substring(0, 10)), refusal);
    assertEquals(before, main(repoId));
  }

  // --- adopting history --------------------------------------------------------------------------

  @Test
  public void aRepositoryWithNoRefAdoptsMalformedHistoryOnce() throws Exception {
    String repoId = GitHostFixture.emptyOrigin(repositories);
    Path local = GitHostFixture.localRepo(); // "first"
    GitHostFixture.commitFile(local, "b.txt", "b\n", "an imported commit, free text");
    GitHostFixture.gitAs(AGENT, local, "git", "push", "-q", gitBase + "/" + repoId, "main");
    assertEquals(GitHostFixture.head(local), main(repoId));

    String adopted = main(repoId);
    GitHostFixture.commitFile(local, "c.txt", "c\n", "and one more, after the import");
    String refusal =
        GitHostFixture.gitExpectingFailureAs(
            AGENT, local, "git", "push", gitBase + "/" + repoId, "main");
    assertExplains(refusal, "and one more, after the import");
    assertEquals(adopted, main(repoId));
  }

  // --- nothing else lets a commit through --------------------------------------------------------

  @Test
  public void theOldOptOutFileChangesNothing() throws Exception {
    String repoId = GitHostFixture.seedOrigin(repositories, gitBase);
    Path clone = GitHostFixture.clone(gitBase, repoId);
    Files.createDirectories(clone.resolve(".config/qits"));
    GitHostFixture.commitFile(
        clone, ".config/qits/commit-subjects.yml", "enforce: false\n", "chore(qits-706): opt out");
    GitHostFixture.git(clone, "git", "push", "-q", "origin", "main");
    String before = main(repoId);

    GitHostFixture.commitFile(clone, "a.txt", "a\n", "wip");
    String refusal =
        GitHostFixture.gitExpectingFailureAs(AGENT, clone, "git", "push", "origin", "main");
    assertExplains(refusal, "wip");
    assertEquals(before, main(repoId));
  }

  @Test
  public void theOldBypassOptionChangesNothing() throws Exception {
    String repoId = GitHostFixture.seedOrigin(repositories, gitBase);
    String before = main(repoId);
    Path clone = GitHostFixture.clone(gitBase, repoId);
    GitHostFixture.commitFile(clone, "a.txt", "a\n", "wip");

    String refusal =
        GitHostFixture.gitExpectingFailureAs(
            AGENT, clone, "git", "push", "-o", "qits.subject-bypass=x", "origin", "main");
    assertExplains(refusal, "wip");
    assertEquals(before, main(repoId));
  }

  @Test
  public void theBypassListIsGone() throws Exception {
    String repoId = GitHostFixture.seedOrigin(repositories, gitBase);
    given()
        .header(TestTokenMechanism.HEADER, TestTokenMechanism.OPERATOR_CLAIMS)
        .get(API + repoId + "/commit-subject-bypasses")
        .then()
        .statusCode(404);
  }

  // --- the REST primitives -----------------------------------------------------------------------

  private static Map<String, Object> commitBody(String message) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("ref", "refs/heads/main");
    body.put("message", message);
    body.put("files", Map.of("VERSION", "2026.1001.1\n"));
    return body;
  }

  private static io.restassured.response.Response post(String repoId, String path, Object body) {
    return given()
        .header(TestTokenMechanism.HEADER, TestTokenMechanism.PLATFORM_SERVICE_CLAIMS)
        .contentType(ContentType.JSON)
        .body(body)
        .when()
        .post(API + repoId + path);
  }

  @Test
  public void theCommitPrimitiveRefusesAFreeTextMessage() throws Exception {
    String repoId = GitHostFixture.seedOrigin(repositories, gitBase);
    String before = main(repoId);
    post(repoId, "/commits", commitBody("Release 2026.1001.1"))
        .then()
        .statusCode(422)
        .body("error", is("malformed-commit-message"))
        .body("detail", containsString("Release 2026.1001.1"))
        .body("detail", containsString(CommitMessageVariants.RELEASE.example()))
        .body("detail", containsString(CommitMessageVariants.SUBJECT.example()));
    assertEquals(before, main(repoId));
  }

  @Test
  public void theCommitPrimitiveAcceptsARelease() throws Exception {
    String repoId = GitHostFixture.seedOrigin(repositories, gitBase);
    String sha =
        post(repoId, "/commits", commitBody("release(2026.1001.1): x"))
            .then()
            .statusCode(200)
            .body("outcome", is("committed"))
            .extract()
            .path("sha");
    assertEquals(sha, main(repoId));
  }

  @Test
  public void theMergePrimitiveWritesAMergeWhateverItsMessage() throws Exception {
    String repoId = GitHostFixture.seedOrigin(repositories, gitBase);
    Path clone = GitHostFixture.clone(gitBase, repoId);
    GitHostFixture.git(clone, "git", "checkout", "-q", "-b", "feature");
    GitHostFixture.commitFile(clone, "b.txt", "b\n", "feat(qits-303): on the branch");
    GitHostFixture.git(clone, "git", "push", "-q", "origin", "feature");
    GitHostFixture.git(clone, "git", "checkout", "-q", "main");
    GitHostFixture.commitFile(clone, "a.txt", "a\n", "fix(qits-303): on main");
    GitHostFixture.git(clone, "git", "push", "-q", "origin", "main");

    Map<String, Object> body = new LinkedHashMap<>();
    body.put("target", "refs/heads/main");
    body.put("sources", List.of("refs/heads/feature"));
    body.put("message", "fold the branch, in free text");
    post(repoId, "/merges", body).then().statusCode(200).body("outcome", is("merged"));
  }
}
