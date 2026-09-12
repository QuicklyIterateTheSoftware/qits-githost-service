package eu.wohlben.qits.githost;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The shipped {@code /git} path policy, which the rest of the suite replaces with {@code permit}.
 *
 * <p>{@code qits:agent} and {@code qits:ci-run} open the door, as {@code qits:admin}, {@code
 * qits:system} and {@code qits:git:external} do. The role decides nothing about a push: the
 * credential's {@code git_refs} list does, and without a list such a credential pushes nothing.
 */
@QuarkusTest
@TestProfile(GitHostPushPolicyTest.ShippedGitPolicy.class)
public class GitHostPushPolicyTest {

  public static class ShippedGitPolicy implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
      return Map.of("quarkus.http.auth.permission.git.policy", "git");
    }
  }

  static final List<String> AGENT =
      TestTokenMechanism.token(
          "{\"sub\":\"dyn-workspace-t-1\",\"groups\":[\"qits:agent\"],"
              + "\"git_refs\":[\"refs/heads/ticket/t-1\"]}");

  static final List<String> CI_RUN =
      TestTokenMechanism.token(
          "{\"sub\":\"dyn-ci-run-1\",\"groups\":[\"qits:ci-run\"],"
              + "\"git_refs\":[\"refs/heads/maintenance/libs\"]}");

  static final List<String> AGENT_WITHOUT_LIST =
      TestTokenMechanism.token("{\"sub\":\"dyn-agent-container-1\",\"groups\":[\"qits:agent\"]}");

  static final String OUTSIDER = "{\"sub\":\"someone\",\"groups\":[\"qits:reader\"]}";

  @Inject GitRepositoryProvider repositories;

  @TestHTTPResource("/git")
  URL gitBase;

  @Test
  public void anAgentRoleOpensTheDoorAndItsListDecidesThePush() throws Exception {
    String repoId = GitHostFixture.seedOrigin(repositories, gitBase);
    String main = sha(repoId, "refs/heads/main");
    Path clone = cloneAs(AGENT, repoId);
    GitHostFixture.commitFile(clone, "ticket.txt", "ticket work\n", "ticket");

    GitHostFixture.gitAs(AGENT, clone, "git", "push", "origin", "HEAD:refs/heads/ticket/t-1");
    assertEquals(GitHostFixture.head(clone), sha(repoId, "refs/heads/ticket/t-1"));

    String refusal =
        GitHostFixture.gitExpectingFailureAs(AGENT, clone, "git", "push", "origin", "HEAD:main");
    assertTrue(refusal.contains("refs/heads/main is outside the push scope"), refusal);
    assertEquals(main, sha(repoId, "refs/heads/main"));
  }

  @Test
  public void aCiRunRoleOpensTheDoorToo() throws Exception {
    String repoId = GitHostFixture.seedOrigin(repositories, gitBase);
    Path clone = cloneAs(CI_RUN, repoId);
    GitHostFixture.commitFile(clone, "pom.xml", "<bumped/>\n", "bump");

    GitHostFixture.gitAs(
        CI_RUN, clone, "git", "push", "origin", "HEAD:refs/heads/maintenance/libs");

    assertEquals(GitHostFixture.head(clone), sha(repoId, "refs/heads/maintenance/libs"));
  }

  @Test
  public void anAgentWithoutAListMayCloneButPushesNothing() throws Exception {
    String repoId = GitHostFixture.seedOrigin(repositories, gitBase);
    Path clone = cloneAs(AGENT_WITHOUT_LIST, repoId);
    GitHostFixture.commitFile(clone, "notes.txt", "read only\n", "notes");

    String refusal =
        GitHostFixture.gitExpectingFailureAs(
            AGENT_WITHOUT_LIST, clone, "git", "push", "origin", "HEAD:refs/heads/ticket/t-1");

    assertTrue(refusal.contains(RefScopeHook.UNSCOPED_CLIENT_REFUSAL), refusal);
    assertNull(sha(repoId, "refs/heads/ticket/t-1"));
  }

  @Test
  public void aRoleOutsideThePolicyIsRefusedAtTheDoor() throws Exception {
    String repoId = GitHostFixture.seedOrigin(repositories, gitBase);

    given()
        .header(TestTokenMechanism.HEADER, OUTSIDER)
        .when()
        .get("/git/" + repoId + "/info/refs?service=git-upload-pack")
        .then()
        .statusCode(403);
    given()
        .header(TestTokenMechanism.HEADER, AGENT_WITHOUT_LIST.get(0).substring(
            (TestTokenMechanism.HEADER + ": ").length()))
        .when()
        .get("/git/" + repoId + "/info/refs?service=git-upload-pack")
        .then()
        .statusCode(200);
  }

  private Path cloneAs(List<String> headers, String repoId) throws Exception {
    Path clone = Files.createTempDirectory("qits-githost-policy-clone");
    Files.delete(clone);
    GitHostFixture.gitAs(headers, null, "git", "clone", "-q", gitBase + "/" + repoId, clone.toString());
    return clone;
  }

  private String sha(String repoId, String ref) throws Exception {
    return GitHostFixture.remoteRefSha(gitBase, repoId, ref);
  }
}
