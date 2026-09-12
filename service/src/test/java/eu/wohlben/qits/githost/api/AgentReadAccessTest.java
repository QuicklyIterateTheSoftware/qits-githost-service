package eu.wohlben.qits.githost.api;

import static io.restassured.RestAssured.given;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.specification.RequestSpecification;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * {@code qits:agent}, a commissioned agent's own role, on the browse API: it passes the {@code
 * githost-browse} policy and may read where a branch stands, and every Git primitive still refuses
 * it.
 *
 * <p>Each request names its identity in {@code X-Qits-User} / {@code X-Qits-Roles}, so the {@code
 * %test} dev user does not apply and the identity holds exactly the role sent. The repository does
 * not exist: a 404 is the resource answering, so the door let the request through.
 */
@QuarkusTest
public class AgentReadAccessTest {

  static final String API = "/githost/api/repositories/";

  private static RequestSpecification as(String role) {
    return given().header("X-Qits-User", "dyn-workspace-agent").header("X-Qits-Roles", role);
  }

  private static RequestSpecification agent() {
    return as("qits:agent");
  }

  @Test
  public void anAgentPassesTheBrowsePolicy() {
    String repo = UUID.randomUUID().toString();
    agent().get(API + repo).then().statusCode(404);
    agent().get(API + repo + "/tags").then().statusCode(404);
    agent().get(API + repo + "/tree").then().statusCode(404);
    agent().get(API + repo + "/file?path=README.md").then().statusCode(404);
    agent().get(API + repo + "/loc").then().statusCode(404);
  }

  @Test
  public void anAgentReadsABranchHead() {
    agent().get(API + UUID.randomUUID() + "/branches/main").then().statusCode(404);
  }

  @Test
  public void anAgentIsRefusedEveryGitPrimitive() {
    String repo = UUID.randomUUID().toString();
    agent()
        .contentType("application/json")
        .body("{\"target\":\"refs/heads/release/1\",\"sources\":[\"refs/heads/main\"]}")
        .post(API + repo + "/merges")
        .then()
        .statusCode(403);
    agent()
        .contentType("application/json")
        .body("{\"name\":\"v1\",\"sha\":\"0000000000000000000000000000000000000000\"}")
        .post(API + repo + "/tags")
        .then()
        .statusCode(403);
    agent()
        .contentType("application/json")
        .body("{}")
        .post(API + repo + "/commits")
        .then()
        .statusCode(403);
    agent().delete(API + repo + "/branches/feature").then().statusCode(403);
  }

  @Test
  public void aRoleOutsideTheBoundaryIsStillRefused() {
    as("qits:reader").get(API + UUID.randomUUID()).then().statusCode(403);
  }
}
