package eu.wohlben.qits.githost;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.githost.persistence.RepositoryNameStore;
import io.quarkus.arc.ClientProxy;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The name door — {@code PUT}/{@code GET /git/:repoId/names} — and the public scheme answering from
 * what it wrote, through the real {@link RepositoryNameStore} and the shipped {@link
 * StoredRepositoryNameResolver}.
 *
 * <p>{@link FakeRepositoryNameResolver} plays the transitional HTTP fallback behind the index. Most
 * cases here make it UNAVAILABLE on purpose: a name that still serves then can only have been served
 * from the index, which is the claim — the public clone url no longer needs qits-projects up.
 */
@QuarkusTest
public class GitHostNamesTest {

  @Inject FakeRepositoryNameResolver fallback;

  @Inject GitRepositoryProvider repositories;

  /** The real store, put back after a case installs a severed one over it. */
  @Inject RepositoryNameStore store;

  @TestHTTPResource("/git")
  URL gitBase;

  @BeforeEach
  void reset() {
    fallback.clear();
  }

  /** See {@code GitHostCatalogUnavailableTest}: a mock installed in a method outlives it. */
  @AfterEach
  void restoreTheRealStore() {
    QuarkusMock.installMockForType(ClientProxy.unwrap(store), RepositoryNameStore.class);
  }

  private static String statement(
      String projectId, String slug, List<String> names, long revision) {
    StringBuilder array = new StringBuilder("[");
    for (int i = 0; i < names.size(); i++) {
      array.append(i == 0 ? "" : ",").append('"').append(names.get(i)).append('"');
    }
    array.append(']');
    return "{\"projectId\":\""
        + projectId
        + "\",\"projectSlug\":\""
        + slug
        + "\",\"names\":"
        + array
        + ",\"revision\":"
        + revision
        + "}";
  }

  private static io.restassured.response.ValidatableResponse put(String repoId, String body) {
    return given()
        .contentType(ContentType.JSON)
        .body(body)
        .when()
        .put("/git/" + repoId + "/names")
        .then();
  }

  private Path cloneOf(String url) throws Exception {
    Path clone = Files.createTempDirectory("qits-githost-names-clone");
    Files.delete(clone);
    GitHostFixture.git(null, "git", "clone", "-q", url, clone.toString());
    return clone;
  }

  @Test
  public void aStatedNameClonesByProjectIdAndBySlugWithTheFallbackUnreachable() throws Exception {
    String repoId = GitHostFixture.seedOrigin(repositories, gitBase);
    String projectId = UUID.randomUUID().toString();
    String slug = "slug-" + UUID.randomUUID();

    put(repoId, statement(projectId, slug, List.of("svc", "alias"), 1))
        .statusCode(200)
        .body("repoId", equalTo(repoId))
        .body("projectId", equalTo(projectId))
        .body("projectSlug", equalTo(slug))
        .body("names", equalTo(List.of("alias", "svc")))
        .body("revision", equalTo(1))
        .body("applied", equalTo(true));

    fallback.beUnavailable(true);
    for (String url :
        List.of(
            gitBase + "/" + projectId + "/svc",
            gitBase + "/" + projectId + "/alias.git",
            gitBase + "/" + slug + "/svc")) {
      Path clone = cloneOf(url);
      assertTrue(Files.exists(clone.resolve(".git")), url + " must clone from the index");
    }
    given()
        .when()
        .get("/git/" + slug + "/svc/tree/main")
        .then()
        .statusCode(Response.Status.OK.getStatusCode());
  }

  @Test
  public void theGetAnswersTheHeldSetAndAnUnstatedRepositoryAnswersEmpty() throws Exception {
    String repoId = GitHostFixture.emptyOrigin(repositories);
    given()
        .when()
        .get("/git/" + repoId + "/names")
        .then()
        .statusCode(200)
        .contentType(ContentType.JSON)
        .body("repoId", equalTo(repoId))
        .body("projectId", nullValue())
        .body("projectSlug", nullValue())
        .body("names", equalTo(List.of()))
        .body("revision", equalTo(-1))
        .body("$", not(hasKey("applied")));

    String projectId = UUID.randomUUID().toString();
    put(repoId, statement(projectId, "slug-" + projectId, List.of("b", "a"), 3)).statusCode(200);
    given()
        .when()
        .get("/git/" + repoId + "/names")
        .then()
        .statusCode(200)
        .body("projectId", equalTo(projectId))
        .body("projectSlug", equalTo("slug-" + projectId))
        .body("names", equalTo(List.of("a", "b")))
        .body("revision", equalTo(3));
  }

  @Test
  public void aStaleStatementIsNotAppliedAndAnswersTheHeldSet() throws Exception {
    String repoId = GitHostFixture.emptyOrigin(repositories);
    String projectId = UUID.randomUUID().toString();
    String slug = "slug-" + projectId;
    put(repoId, statement(projectId, slug, List.of("current"), 7)).statusCode(200);

    put(repoId, statement(projectId, slug, List.of("older"), 6))
        .statusCode(200)
        .body("applied", equalTo(false))
        .body("names", equalTo(List.of("current")))
        .body("revision", equalTo(7));

    assertEquals(Optional.empty(), store.resolve(projectId, "older"));
  }

  @Test
  public void anUnknownRepositoryIs404AndNothingIsWritten() {
    String repoId = UUID.randomUUID().toString();
    String projectId = UUID.randomUUID().toString();
    put(repoId, statement(projectId, "slug-" + projectId, List.of("ghost"), 1)).statusCode(404);
    given().when().get("/git/" + repoId + "/names").then().statusCode(404);

    assertEquals(Optional.empty(), store.resolve(projectId, "ghost"));
    assertTrue(store.namesOf(repoId).isEmpty());
  }

  @Test
  public void aMalformedStatementIs400() throws Exception {
    String repoId = GitHostFixture.emptyOrigin(repositories);
    String p = UUID.randomUUID().toString();
    String longest = "x".repeat(255);
    for (String body :
        List.of(
            "",
            "not json",
            "[]",
            "{\"projectSlug\":\"s\",\"names\":[],\"revision\":1}",
            "{\"projectId\":\" \",\"projectSlug\":\"s\",\"names\":[],\"revision\":1}",
            "{\"projectId\":\"" + "p".repeat(65) + "\",\"projectSlug\":\"s\",\"names\":[],"
                + "\"revision\":1}",
            "{\"projectId\":\"" + p + "\",\"names\":[],\"revision\":1}",
            "{\"projectId\":\"" + p + "\",\"projectSlug\":\"" + longest + "x\",\"names\":[],"
                + "\"revision\":1}",
            "{\"projectId\":\"" + p + "\",\"projectSlug\":\"s\",\"revision\":1}",
            "{\"projectId\":\"" + p + "\",\"projectSlug\":\"s\",\"names\":\"a\",\"revision\":1}",
            statement(p, "s", List.of(""), 1),
            statement(p, "s", List.of("a/b"), 1),
            statement(p, "s", List.of("a b"), 1),
            statement(p, "s", List.of("a.git"), 1),
            statement(p, "s", List.of(longest + "x"), 1),
            "{\"projectId\":\"" + p + "\",\"projectSlug\":\"s\",\"names\":[1],\"revision\":1}",
            "{\"projectId\":\"" + p + "\",\"projectSlug\":\"s\",\"names\":[]}",
            "{\"projectId\":\"" + p + "\",\"projectSlug\":\"s\",\"names\":[],\"revision\":-1}",
            "{\"projectId\":\"" + p + "\",\"projectSlug\":\"s\",\"names\":[],\"revision\":1.5}",
            "{\"projectId\":\"" + p + "\",\"projectSlug\":\"s\",\"names\":[],\"revision\":\"1\"}")) {
      put(repoId, body).statusCode(400);
    }
    put("bad.id", statement(p, "s", List.of("a"), 1))
        .statusCode(400)
        .body(containsString("repo id must match"));
    assertTrue(store.namesOf(repoId).isEmpty(), "a refused statement writes nothing");

    // The bounds themselves are accepted.
    put(repoId, statement("p".repeat(64), "s-" + p, List.of(longest), 0)).statusCode(200);
  }

  @Test
  public void deletingTheRepositoryDropsItsNames() throws Exception {
    String repoId = GitHostFixture.seedOrigin(repositories, gitBase);
    String projectId = UUID.randomUUID().toString();
    put(repoId, statement(projectId, "slug-" + projectId, List.of("svc"), 1)).statusCode(200);
    given()
        .when()
        .get("/git/" + projectId + "/svc/info/refs?service=git-upload-pack")
        .then()
        .statusCode(200);

    given().when().delete("/git/" + repoId).then().statusCode(204);

    given()
        .when()
        .get("/git/" + projectId + "/svc/info/refs?service=git-upload-pack")
        .then()
        .statusCode(Response.Status.NOT_FOUND.getStatusCode());
    assertTrue(store.namesOf(repoId).isEmpty());
  }

  @Test
  public void aRepositoryCalledNamesStillClonesByName() throws Exception {
    // The door is /git/<repoId>/names; /git/<project>/names/info/refs is three segments under the
    // base and belongs to the name-addressed clone route. Stated through the door itself.
    String repoId = GitHostFixture.seedOrigin(repositories, gitBase);
    String projectId = UUID.randomUUID().toString();
    put(repoId, statement(projectId, "slug-" + projectId, List.of("names"), 1)).statusCode(200);

    Path clone = cloneOf(gitBase + "/" + projectId + "/names");
    assertTrue(Files.exists(clone.resolve(".git")), "a repository named names must still clone");
    given()
        .when()
        .get("/git/" + projectId + "/names/tree/main")
        .then()
        .statusCode(Response.Status.OK.getStatusCode());
  }

  @Test
  public void aMissInTheIndexFallsBackToQitsProjects() throws Exception {
    String repoId = GitHostFixture.seedOrigin(repositories, gitBase);
    String projectId = UUID.randomUUID().toString();
    fallback.register(projectId, "only-upstream", repoId);

    Path clone = cloneOf(gitBase + "/" + projectId + "/only-upstream");
    assertTrue(Files.exists(clone.resolve(".git")), "the fallback must still answer a miss");

    fallback.beUnavailable(true);
    given()
        .when()
        .get("/git/" + projectId + "/only-upstream/info/refs?service=git-upload-pack")
        .then()
        .statusCode(Response.Status.SERVICE_UNAVAILABLE.getStatusCode());
  }

  @Test
  public void anIndexThatCannotBeReadIs503AndTheFallbackIsNotAsked() throws Exception {
    // A fault is not a miss: the fallback holds the name here, and still the answer is a 503 —
    // never a 404, and never a guess from the second source.
    String repoId = GitHostFixture.seedOrigin(repositories, gitBase);
    String projectId = UUID.randomUUID().toString();
    fallback.register(projectId, "svc", repoId);
    QuarkusMock.installMockForType(new SeveredStore(), RepositoryNameStore.class);

    given()
        .when()
        .get("/git/" + projectId + "/svc/info/refs?service=git-upload-pack")
        .then()
        .statusCode(Response.Status.SERVICE_UNAVAILABLE.getStatusCode());
    given()
        .when()
        .get("/git/" + projectId + "/svc/blob/main/README.md")
        .then()
        .statusCode(Response.Status.SERVICE_UNAVAILABLE.getStatusCode());
  }

  /** A store whose database is gone and stays gone. */
  public static class SeveredStore extends RepositoryNameStore {

    @Override
    public Optional<String> resolve(String projectSegment, String name) {
      throw GitHostCatalogUnavailableTest.connectionLost();
    }
  }
}
