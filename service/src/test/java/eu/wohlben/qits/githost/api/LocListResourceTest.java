package eu.wohlben.qits.githost.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.fail;

import eu.wohlben.qits.githost.GitRepositoryProvider;
import eu.wohlben.qits.githost.contracts.ProviderStates;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * {@code GET /githost/api/loc}: one row per repository, answered from the memo, with a missing
 * summary queued rather than computed in the request.
 */
@QuarkusTest
public class LocListResourceTest {

  private static final String API = "/githost/api/loc";

  @Inject ProviderStates states;
  @Inject GitRepositoryProvider repositories;

  private String repositoryIn(String state) {
    return states.params(state).get("repositoryId");
  }

  @Test
  public void aCountedRepositoryCarriesItsNumbers() {
    String id = repositoryIn(ProviderStates.A_REPOSITORY_WITH_COUNTED_LINES);
    given().queryParam("repositoryId", id).when().get(API).then().statusCode(200)
        .body("entries.repositoryId", contains(id))
        .body("entries[0].status", equalTo("COUNTED"))
        // The lockfile and the generated client (web/api/**) are seeded but not counted.
        .body("entries[0].languages.language", contains("Java", "TypeScript", "JSON", "Markdown"))
        .body("entries[0].languages.category", contains("CODE", "CODE", "DATA", "DOCS"))
        .body("entries[0].languages[1].mainLines", equalTo(4))
        .body("entries[0].languages[0].mainLines", equalTo(3))
        .body("entries[0].languages[0].testLines", equalTo(2));
  }

  @Test
  public void anUncountedRepositoryIsPendingAndTheCallQueuesItsScan() throws Exception {
    String id = repositoryIn(ProviderStates.A_REPOSITORY_NOT_COUNTED_YET);
    given().queryParam("repositoryId", id).when().get(API).then().statusCode(200)
        .body("entries[0].status", equalTo("PENDING"))
        .body("entries[0].commitSha", not(equalTo(null)))
        .body("entries[0].languages", empty());

    long deadline = System.nanoTime() + 10_000_000_000L;
    while (System.nanoTime() < deadline) {
      String status =
          given().queryParam("repositoryId", id).when().get(API).then().statusCode(200)
              .extract().path("entries[0].status");
      if ("COUNTED".equals(status)) {
        return;
      }
      Thread.sleep(50);
    }
    fail("the pending repository " + id + " was never counted");
  }

  @Test
  public void aRepositoryWithNoCommitIsEmpty() {
    String id = repositoryIn(ProviderStates.A_REPOSITORY_WITH_NO_COMMIT);
    given().queryParam("repositoryId", id).when().get(API).then().statusCode(200)
        .body("entries[0].status", equalTo("EMPTY"))
        .body("entries[0].commitSha", equalTo(null))
        .body("entries[0].languages", empty());
  }

  @Test
  public void withoutAFilterEveryRepositoryIsListedSortedById() {
    String counted = repositoryIn(ProviderStates.A_REPOSITORY_WITH_COUNTED_LINES);
    String empty = repositoryIn(ProviderStates.A_REPOSITORY_WITH_NO_COMMIT);
    var ids =
        given().when().get(API).then().statusCode(200).extract().<String>jsonPath()
            .getList("entries.repositoryId", String.class);
    if (!ids.contains(counted) || !ids.contains(empty)) {
      fail("the unfiltered list misses a repository: " + ids);
    }
    if (!ids.equals(ids.stream().sorted().toList())) {
      fail("the list is not sorted by id: " + ids);
    }
  }

  @Test
  public void aNamedRepositoryThisHostDoesNotHoldIsLeftOut() {
    String held = repositoryIn(ProviderStates.A_REPOSITORY_WITH_NO_COMMIT);
    String unknown = UUID.randomUUID().toString();
    given().queryParam("repositoryId", held, unknown).when().get(API).then().statusCode(200)
        .body("entries.repositoryId", contains(held))
        .body("entries.repositoryId", not(hasItem(unknown)));
  }

  @Test
  public void anInvalidRepositoryIdIsABadRequest() {
    given().queryParam("repositoryId", "..%2Fetc").when().get(API).then().statusCode(400);
  }

  @Test
  public void aTipNotCountedYetAnswersTheNewestOlderCountAsStaleAndQueuesTheTip() throws Exception {
    String id = repositoryIn(ProviderStates.A_REPOSITORY_COUNTED_AT_AN_OLDER_COMMIT);
    String tip;
    try (var repo = repositories.open(id)) {
      tip = repo.exactRef("HEAD").getObjectId().name();
    }
    String counted =
        given().queryParam("repositoryId", id).when().get(API).then().statusCode(200)
            .body("entries[0].status", equalTo("STALE"))
            .body("entries[0].languages.language", contains("Java", "TypeScript", "JSON", "Markdown"))
            .body("entries[0].languages[1].mainLines", equalTo(4))
            .extract().path("entries[0].commitSha");
    org.junit.jupiter.api.Assertions.assertNotEquals(tip, counted);

    long deadline = System.nanoTime() + 10_000_000_000L;
    while (System.nanoTime() < deadline) {
      var answer = given().queryParam("repositoryId", id).when().get(API).then().statusCode(200);
      if ("COUNTED".equals(answer.extract().path("entries[0].status"))) {
        // The tip's extra TypeScript line: 5 main lines, which now outranks Java.
        answer.body("entries[0].commitSha", equalTo(tip))
            .body("entries[0].languages.find { it.language == 'TypeScript' }.mainLines", equalTo(5));
        return;
      }
      Thread.sleep(50);
    }
    org.junit.jupiter.api.Assertions.fail("the tip of " + id + " was never counted");
  }
}
