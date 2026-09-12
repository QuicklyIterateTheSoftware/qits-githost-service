package eu.wohlben.qits.githost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.net.URL;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * C3 over the wire: a real {@code git push} per credential shape, and what the origin holds after.
 *
 * <p>The scope is captured on the event loop and checked on the JGit worker, so only a push through
 * the served routes proves the two halves meet. Identities come from {@link TestTokenMechanism}
 * (JWT claims) or from the forwarded headers qits-auth-core reads. No profile: protection of the
 * default branch is off here, so every refusal below is the scope's.
 */
@QuarkusTest
public class GitHostRefScopeTest {

  /** A ticket agent that still carries its owner's roles: the roles must not widen its list. */
  static final List<String> TICKET_AGENT =
      TestTokenMechanism.token(
          "{\"sub\":\"dyn-workspace-t-1\",\"groups\":[\"qits:admin\",\"qits:system\"],"
              + "\"context_kind\":\"workspace\",\"git_refs\":[\"refs/heads/ticket/t-1\"]}");

  /**
   * An epic agent: its own branch exactly, and its features by prefix. (A prefix under the epic
   * branch itself would name refs git cannot hold beside that branch.)
   */
  static final List<String> EPIC_AGENT =
      TestTokenMechanism.token(
          "{\"sub\":\"dyn-workspace-e-1\",\"groups\":[\"qits:system\"],"
              + "\"git_refs\":[\"refs/heads/epic/e-1\",\"refs/heads/feature/e-1/*\"]}");

  /** A person's {@code qits} CLI token, as the idp mints it before it states git_refs. */
  static final List<String> CLI_PERSON =
      TestTokenMechanism.token(
          "{\"sub\":\"alice\",\"aud\":[\"qits-platform\"],\"groups\":[\"qits:admin\"],"
              + "\"credential_type\":\"cli\"}");

  /** The workstation token, with qits:admin beside the external role. */
  static final List<String> WORKSTATION =
      TestTokenMechanism.token(
          "{\"sub\":\"alice\",\"groups\":[\"qits:git:external\",\"qits:admin\"],"
              + "\"git_ref_pattern\":\"refs/heads/external/*\"}");

  /** A browser session: the forwarded headers, no JWT. */
  static final List<String> BROWSER_SESSION =
      List.of("X-Qits-User: alice", "X-Qits-Roles: qits:admin,qits:system");

  /** A static platform service: a client token with no scope. */
  static final List<String> STATIC_CLIENT =
      TestTokenMechanism.token("{\"sub\":\"qits-projects\",\"groups\":[\"qits:system\"]}");

  private static final String PERSON_SCOPE =
      "a person's credential may push only refs/heads/external/*";

  @Inject GitRepositoryProvider repositories;

  @TestHTTPResource("/git")
  URL gitBase;

  @Test
  public void anAgentPushesItsOwnBranchWhateverItsRoles() throws Exception {
    String repoId = seed();
    Path clone = cloneWithACommit(repoId);

    GitHostFixture.gitAs(TICKET_AGENT, clone, "git", "push", "origin", "HEAD:refs/heads/ticket/t-1");

    assertEquals(GitHostFixture.head(clone), sha(repoId, "refs/heads/ticket/t-1"));
  }

  @Test
  public void anAgentIsRefusedAnotherBranchAndTheDefaultBranch() throws Exception {
    String repoId = seed();
    String main = sha(repoId, "refs/heads/main");
    Path clone = cloneWithACommit(repoId);

    String epic =
        GitHostFixture.gitExpectingFailureAs(
            TICKET_AGENT, clone, "git", "push", "origin", "HEAD:refs/heads/epic/e-1");
    assertTrue(
        epic.contains(
            "refs/heads/epic/e-1 is outside the push scope: this credential may push only"
                + " refs/heads/ticket/t-1"),
        epic);
    assertNull(sha(repoId, "refs/heads/epic/e-1"));

    String toMain =
        GitHostFixture.gitExpectingFailureAs(TICKET_AGENT, clone, "git", "push", "origin", "main");
    assertTrue(toMain.contains("refs/heads/main is outside the push scope"), toMain);
    assertEquals(main, sha(repoId, "refs/heads/main"));
  }

  @Test
  public void aPushWithOneRefusedRefLandsNothing() throws Exception {
    String repoId = seed();
    Path clone = cloneWithACommit(repoId);

    String refusal =
        GitHostFixture.gitExpectingFailureAs(
            TICKET_AGENT,
            clone,
            "git",
            "push",
            "origin",
            "HEAD:refs/heads/ticket/t-1",
            "HEAD:refs/heads/epic/e-1");

    assertTrue(
        refusal.contains("refused with the whole push, because refs/heads/epic/e-1 is outside"),
        refusal);
    assertNull(sha(repoId, "refs/heads/ticket/t-1"), "the allowed ref must not land either");
    assertNull(sha(repoId, "refs/heads/epic/e-1"));
  }

  @Test
  public void aPrefixEntryCoversTheBranchesUnderIt() throws Exception {
    String repoId = seed();
    Path clone = cloneWithACommit(repoId);
    String head = GitHostFixture.head(clone);

    GitHostFixture.gitAs(
        EPIC_AGENT,
        clone,
        "git",
        "push",
        "origin",
        "HEAD:refs/heads/epic/e-1",
        "HEAD:refs/heads/feature/e-1/f-2",
        "HEAD:refs/heads/feature/e-1/f-3/part");
    assertEquals(head, sha(repoId, "refs/heads/epic/e-1"));
    assertEquals(head, sha(repoId, "refs/heads/feature/e-1/f-2"));
    assertEquals(head, sha(repoId, "refs/heads/feature/e-1/f-3/part"));

    for (String outside : List.of("refs/heads/epic/e-10", "refs/heads/feature/e-10/f-1")) {
      String refusal =
          GitHostFixture.gitExpectingFailureAs(
              EPIC_AGENT, clone, "git", "push", "origin", "HEAD:" + outside);
      assertTrue(refusal.contains(outside + " is outside the push scope"), refusal);
      assertNull(sha(repoId, outside));
    }
  }

  @Test
  public void anAgentCannotPushATag() throws Exception {
    String repoId = seed();
    Path clone = cloneWithACommit(repoId);
    GitHostFixture.git(clone, "git", "tag", "v1");

    String refusal =
        GitHostFixture.gitExpectingFailureAs(
            TICKET_AGENT, clone, "git", "push", "origin", "refs/tags/v1");

    assertTrue(refusal.contains("refs/tags/v1 is outside the push scope"), refusal);
    assertNull(sha(repoId, "refs/tags/v1"));
  }

  @Test
  public void aPersonWithTheCliTokenPushesOnlyExternalBranches() throws Exception {
    assertPersonPushesOnlyExternalBranches(CLI_PERSON);
  }

  @Test
  public void aPersonInABrowserSessionPushesOnlyExternalBranches() throws Exception {
    assertPersonPushesOnlyExternalBranches(BROWSER_SESSION);
  }

  @Test
  public void theSuitesSyntheticUserIsAPersonToo() throws Exception {
    // No header at all: qits-auth-core's %test synthetic user (qits:admin, qits:system, no JWT).
    assertPersonPushesOnlyExternalBranches(List.of());
  }

  @Test
  public void aWorkstationTokenWithAdminIsRestrictedNow() throws Exception {
    // Before C3 qits:admin beside qits:git:external lifted the restriction.
    String repoId = seed();
    String main = sha(repoId, "refs/heads/main");
    Path clone = cloneWithACommit(repoId);

    String refusal =
        GitHostFixture.gitExpectingFailureAs(WORKSTATION, clone, "git", "push", "origin", "main");
    assertTrue(
        refusal.contains(
            "refs/heads/main is outside the push scope: an external workstation credential may"
                + " push only refs/heads/external/*"),
        refusal);
    assertEquals(main, sha(repoId, "refs/heads/main"));

    GitHostFixture.gitAs(
        WORKSTATION, clone, "git", "push", "origin", "HEAD:refs/heads/external/alice/topic");
    assertEquals(GitHostFixture.head(clone), sha(repoId, "refs/heads/external/alice/topic"));
  }

  @Test
  public void aStaticClientIsNotRestricted() throws Exception {
    String repoId = seed();
    Path clone = cloneWithACommit(repoId);
    GitHostFixture.git(clone, "git", "tag", "v1");

    GitHostFixture.gitAs(STATIC_CLIENT, clone, "git", "push", "origin", "main", "refs/tags/v1");

    assertEquals(GitHostFixture.head(clone), sha(repoId, "refs/heads/main"));
    assertEquals(GitHostFixture.head(clone), sha(repoId, "refs/tags/v1"));
  }

  @Test
  public void aClientWithoutAScopeAndWithoutSystemPushesNothing() throws Exception {
    // A future qits:agent commission that states no list: it must not fall through to unrestricted.
    List<String> agentWithoutList =
        TestTokenMechanism.token(
            "{\"sub\":\"dyn-agent-container-1\",\"groups\":[\"qits:agent\"],"
                + "\"context_kind\":\"agent-container\"}");
    String repoId = seed();
    String main = sha(repoId, "refs/heads/main");
    Path clone = cloneWithACommit(repoId);

    for (String refspec : List.of("main", "HEAD:refs/heads/external/alice/topic")) {
      String refusal =
          GitHostFixture.gitExpectingFailureAs(
              agentWithoutList, clone, "git", "push", "origin", refspec);
      assertTrue(refusal.contains(RefScopeHook.UNSCOPED_CLIENT_REFUSAL), refusal);
    }
    assertEquals(main, sha(repoId, "refs/heads/main"));
    assertNull(sha(repoId, "refs/heads/external/alice/topic"));
  }

  private void assertPersonPushesOnlyExternalBranches(List<String> person) throws Exception {
    String repoId = seed();
    String main = sha(repoId, "refs/heads/main");
    Path clone = cloneWithACommit(repoId);

    String refusal =
        GitHostFixture.gitExpectingFailureAs(person, clone, "git", "push", "origin", "main");
    assertTrue(
        refusal.contains("refs/heads/main is outside the push scope: " + PERSON_SCOPE), refusal);
    assertEquals(main, sha(repoId, "refs/heads/main"));

    GitHostFixture.gitAs(
        person, clone, "git", "push", "origin", "HEAD:refs/heads/external/alice/topic");
    assertEquals(GitHostFixture.head(clone), sha(repoId, "refs/heads/external/alice/topic"));
  }

  private String seed() throws Exception {
    return GitHostFixture.seedOrigin(repositories, gitBase);
  }

  private Path cloneWithACommit(String repoId) throws Exception {
    Path clone = GitHostFixture.clone(gitBase, repoId);
    GitHostFixture.commitFile(clone, "work.txt", "work\n", "work");
    return clone;
  }

  private String sha(String repoId, String ref) throws Exception {
    return GitHostFixture.remoteRefSha(gitBase, repoId, ref);
  }
}
