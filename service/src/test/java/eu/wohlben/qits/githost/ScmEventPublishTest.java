package eu.wohlben.qits.githost;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.eventstream.entity.OutboxEvent;
import io.quarkus.hibernate.orm.PersistenceUnit;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import java.net.URL;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * A real push, through the real routes, ending as rows in the real outbox.
 *
 * <p>This is the whole seam the post-receive HTTP fan-out used to be, asserted end to end: git
 * speaks receive-pack, {@code GitHostRoutes} runs the hook, {@code ScmEventAnnouncer} maps the
 * commands and {@code QitsEventBus} takes the events. {@link
 * eu.wohlben.qits.githost.bus.PostReceiveEventsTest} covers the mapping case by case; what is under
 * test here is that the wiring exists and that a push really produces publishes.
 *
 * <p><b>The bus is aimed at a CLOSED PORT, deliberately, and that is what makes this assertable.</b>
 * {@code QitsEventBus.publish} attempts the idempotent PUT inline and hands anything that does not
 * land to the outbox — so against a port nothing answers, every published event becomes exactly one
 * row, whole, with the canonical payload it would have been sent with. Standing up a stub events
 * server would prove the same thing and add a second moving part; a row IS the publish, from this
 * side of the bus.
 *
 * <p>The suite's scheduler is off (see the test properties), so no sweeper retries a row out from
 * under an assertion.
 *
 * <p>The REST write doors of {@code api.RepositoryRefsResource} are asserted here too, against the
 * same outbox: a merge, a commit, a tag and a branch deletion through them must publish exactly what
 * the equivalent push publishes, under the address the caller named, and a write that moved nothing
 * must publish nothing.
 *
 * <p>A class of its own rather than more cases in {@link GitHostTest}, because a bus that is on is a
 * different process configuration and {@code @TestProfile} is per class — the same reason the
 * push-token cases split.
 */
@QuarkusTest
@TestProfile(ScmEventPublishTest.BusEnabled.class)
public class ScmEventPublishTest {

  public static class BusEnabled implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
      return Map.of(
          "qits.eventstream.enabled", "true",
          // Nothing answers here. A refused connection is immediate, so the inline attempt costs
          // no wall time and the event lands in the outbox rather than on a socket.
          "qits.events.url", "http://localhost:1");
    }
  }

  @Inject GitRepositoryProvider repositories;

  /** The alias table, so a push can arrive on the public {@code /git/<project>/<name>} url. */
  @Inject FakeRepositoryNameResolver repositoryNames;

  /**
   * The outbox is read through its OWN persistence unit's EntityManager rather than through
   * Panache's static methods: {@code OutboxEvent} arrives from the qits-eventstream jar, and a
   * Panache static on an entity this application did not compile is not enhanced here — it throws
   * "did you forget to annotate your entity with @Entity?", which names the wrong problem. The
   * qualifier is what picks the eventstream unit; this service's own entities are in another.
   */
  @Inject
  @PersistenceUnit("eventstream")
  EntityManager outbox;

  /** Armed by the one case that proves an announcer's failure never fails a door. */
  @Inject FailingAnnouncer failingAnnouncer;

  @TestHTTPResource("/git")
  URL gitBase;

  private static final String API = "/githost/api/repositories/";

  @BeforeEach
  void forgetPreviousPublishes() {
    QuarkusTransaction.requiringNew()
        .run(() -> outbox.createQuery("delete from OutboxEvent").executeUpdate());
  }

  @Test
  public void anOrdinaryPushPublishesOnePublishCommitCarryingTheHeadsFacts() throws Exception {
    String repoId = GitHostFixture.seedOrigin(repositories, gitBase);
    Path clone = GitHostFixture.clone(gitBase, repoId);
    forgetPreviousPublishes(); // seedOrigin's own push already published

    GitHostFixture.commitFile(clone, "ordinary.txt", "an ordinary push\n", "ordinary");
    String head = GitHostFixture.head(clone);
    GitHostFixture.git(clone, "git", "push", "origin", "main");

    OutboxEvent published = only("SCMPublishCommit");
    assertTrue(published.payload.contains("\"branch\":\"main\""), published.payload);
    assertTrue(published.payload.contains("\"sha\":\"" + head + "\""), published.payload);
    assertTrue(published.payload.contains("\"repoId\":\"" + repoId + "\""), published.payload);
    // The half the HTTP event never carried: the head's own metadata, read off the pack.
    assertTrue(published.payload.contains("\"authorEmail\":\"qits@local\""), published.payload);
    assertTrue(published.payload.contains("\"parents\":["), published.payload);
    assertNotNull(published.occurredAt);
  }

  @Test
  public void aNameAddressedPushEchoesTheAddressOntoItsEvents() throws Exception {
    // The trick this whole campaign rests on: the public clone url IS (projectId, repoName), so the
    // route holds both coordinates when the push lands and the event carries them without this host
    // resolving, storing or knowing anything about names.
    String projectId = UUID.randomUUID().toString();
    String repoId = GitHostFixture.seedOrigin(repositories, gitBase);
    repositoryNames.register(projectId, "testing-repo", repoId);
    String address = projectId + "/testing-repo";
    Path clone = GitHostFixture.clone(gitBase, address);
    forgetPreviousPublishes();

    GitHostFixture.commitFile(clone, "named.txt", "pushed by name\n", "named");
    String tagObject = GitHostFixture.tagObject(clone, "v2026.821.1", "release");
    GitHostFixture.git(
        clone,
        "git",
        "push",
        "--atomic",
        "origin",
        "HEAD:refs/heads/main",
        tagObject + ":refs/tags/v2026.821.1");

    OutboxEvent commit = only("SCMPublishCommit");
    assertTrue(commit.payload.contains("\"projectId\":\"" + projectId + "\""), commit.payload);
    assertTrue(commit.payload.contains("\"repoName\":\"testing-repo\""), commit.payload);
    assertTrue(commit.payload.contains("\"repoId\":\"" + repoId + "\""), commit.payload);

    OutboxEvent tag = only("SCMPublishTag");
    assertTrue(tag.payload.contains("\"projectId\":\"" + projectId + "\""), tag.payload);
    assertTrue(tag.payload.contains("\"repoName\":\"testing-repo\""), tag.payload);
  }

  @Test
  public void anIdAddressedPushAnnouncesWithNoNameFieldsAtAll() throws Exception {
    // The internal scheme is qits-projects' mirror syncing history the platform already announced
    // under its public name. The push is real, the event is owed, and inventing a name for it would
    // be worse than leaving the two keys out.
    String repoId = GitHostFixture.seedOrigin(repositories, gitBase);
    Path clone = GitHostFixture.clone(gitBase, repoId);
    forgetPreviousPublishes();

    GitHostFixture.commitFile(clone, "mirrored.txt", "pushed by id\n", "mirrored");
    GitHostFixture.git(clone, "git", "push", "origin", "main");

    OutboxEvent published = only("SCMPublishCommit");
    assertFalse(published.payload.contains("projectId"), published.payload);
    assertFalse(published.payload.contains("repoName"), published.payload);
    assertTrue(published.payload.contains("\"repoId\":\"" + repoId + "\""), published.payload);
  }

  @Test
  public void oneReleasePushOfABranchAndAnAnnotatedTagPublishesBoth() throws Exception {
    String repoId = GitHostFixture.seedOrigin(repositories, gitBase);
    Path clone = GitHostFixture.clone(gitBase, repoId);
    GitHostFixture.commitFile(clone, "VERSION", "2026.810.1\n", "release");
    String released = GitHostFixture.head(clone);
    String tagObject = GitHostFixture.tagObject(clone, "v2026.810.1", "release");
    forgetPreviousPublishes();

    GitHostFixture.git(
        clone,
        "git",
        "push",
        "--atomic",
        "origin",
        "HEAD:refs/heads/main",
        tagObject + ":refs/tags/v2026.810.1");

    OutboxEvent commit = only("SCMPublishCommit");
    assertTrue(commit.payload.contains("\"sha\":\"" + released + "\""), commit.payload);

    OutboxEvent tag = only("SCMPublishTag");
    assertTrue(tag.payload.contains("\"tagName\":\"v2026.810.1\""), tag.payload);
    assertTrue(tag.payload.contains("\"annotated\":true"), tag.payload);
    assertTrue(tag.payload.contains("\"sha\":\"" + tagObject + "\""), tag.payload);
    assertTrue(tag.payload.contains("\"targetSha\":\"" + released + "\""), tag.payload);
  }

  @Test
  public void deletingABranchPublishesItsOwnEvent() throws Exception {
    String repoId = GitHostFixture.seedOrigin(repositories, gitBase);
    Path clone = GitHostFixture.clone(gitBase, repoId);
    GitHostFixture.git(clone, "git", "checkout", "-q", "-b", "feature/x");
    GitHostFixture.commitFile(clone, "feature.txt", "work\n", "work");
    String tip = GitHostFixture.head(clone);
    GitHostFixture.git(clone, "git", "push", "-q", "origin", "feature/x");
    forgetPreviousPublishes();

    GitHostFixture.git(clone, "git", "push", "-q", "origin", "--delete", "feature/x");

    OutboxEvent deleted = only("SCMDeleteBranch");
    assertTrue(deleted.payload.contains("\"branch\":\"feature/x\""), deleted.payload);
    assertTrue(deleted.payload.contains("\"sha\":\"" + tip + "\""), deleted.payload);
    // A deletion moves no objects, so it is the only thing the push says.
    assertEquals(1, rows().size(), "a delete publishes a deletion and nothing else");
  }

  @Test
  public void aPushCarryingACauseChainsItsEventsOntoIt() throws Exception {
    // qits-eventstream propagates a cause across an HTTP hop with a pair of JAX-RS filters. A push
    // is not a JAX-RS request — the git routes are raw Vert.x, which no filter sees, while the REST
    // doors at /githost/api are JAX-RS and get it for free — so GitHostRoutes reads the header
    // itself, and this is what says the hand-rolled half really works, over a real `git push` rather
    // than a synthetic request. `http.extraHeader` is git's own way of putting one on every HTTP request it makes.
    String cause = UUID.randomUUID().toString();
    String repoId = GitHostFixture.seedOrigin(repositories, gitBase);
    Path clone = GitHostFixture.clone(gitBase, repoId);
    forgetPreviousPublishes();

    GitHostFixture.commitFile(clone, "caused.txt", "because of something\n", "caused");
    GitHostFixture.git(
        clone, "git", "-c", "http.extraHeader=X-Qits-Causation-Id: " + cause, "push", "origin",
        "main");

    assertEquals(cause, only("SCMPublishCommit").parentId);
  }

  @Test
  public void aPushWithNoCauseStartsItsOwnChain() throws Exception {
    String repoId = GitHostFixture.seedOrigin(repositories, gitBase);
    Path clone = GitHostFixture.clone(gitBase, repoId);
    forgetPreviousPublishes();

    GitHostFixture.commitFile(clone, "uncaused.txt", "nobody asked\n", "uncaused");
    GitHostFixture.git(clone, "git", "push", "origin", "main");

    assertNull(only("SCMPublishCommit").parentId, "an ordinary push is a chain root");
  }

  @Test
  public void aMalformedCauseIsReadAsNoneRatherThanRefused() throws Exception {
    // Causation is advisory: a push must never fail over a field only the chain graph reads. Same
    // leniency CausationHeader.parse has, restated here because this service replicates it.
    String repoId = GitHostFixture.seedOrigin(repositories, gitBase);
    Path clone = GitHostFixture.clone(gitBase, repoId);
    forgetPreviousPublishes();

    GitHostFixture.commitFile(clone, "malformed.txt", "not a uuid\n", "malformed");
    GitHostFixture.git(
        clone, "git", "-c", "http.extraHeader=X-Qits-Causation-Id: not-a-uuid", "push", "origin",
        "main");

    assertNull(only("SCMPublishCommit").parentId, "a malformed cause reads exactly like none");
  }

  // --- the REST write doors ---------------------------------------------------------------------

  @Test
  public void aMergeThroughTheDoorAnnouncesTheTargetLikeAPushWould() throws Exception {
    // The release flow's folds and its finalize merge into main move refs through this door. A
    // consumer keyed on "main moved" — qits-maintenance's rescan — must hear it exactly as it would
    // hear a push: the short branch name, both shas, and the public address the caller named.
    String projectId = UUID.randomUUID().toString();
    String repoId = GitHostFixture.seedOrigin(repositories, gitBase);
    Path clone = GitHostFixture.clone(gitBase, repoId);
    String before = GitHostFixture.head(clone);
    GitHostFixture.git(clone, "git", "checkout", "-q", "-b", "feature/x");
    GitHostFixture.commitFile(clone, "feature.txt", "work\n", "work");
    String feature = GitHostFixture.head(clone);
    GitHostFixture.git(clone, "git", "push", "-q", "origin", "feature/x");
    forgetPreviousPublishes();

    Map<String, Object> body = new LinkedHashMap<>();
    body.put("target", "refs/heads/main");
    body.put("sources", List.of("refs/heads/feature/x"));
    body.put("projectId", projectId);
    body.put("repoName", "testing-repo");
    post(repoId, "/merges", body).then().statusCode(200);

    OutboxEvent published = only("SCMPublishCommit");
    assertTrue(published.payload.contains("\"branch\":\"main\""), published.payload);
    assertTrue(published.payload.contains("\"oldSha\":\"" + before + "\""), published.payload);
    assertTrue(published.payload.contains("\"sha\":\"" + feature + "\""), published.payload);
    assertTrue(published.payload.contains("\"repoId\":\"" + repoId + "\""), published.payload);
    assertTrue(
        published.payload.contains("\"projectId\":\"" + projectId + "\""), published.payload);
    assertTrue(published.payload.contains("\"repoName\":\"testing-repo\""), published.payload);
    assertEquals(1, rows().size(), "one ref moved, so one event");
  }

  @Test
  public void anOctopusOntoANewTargetIsAnnouncedAsItsCreation() throws Exception {
    String repoId = GitHostFixture.seedOrigin(repositories, gitBase);
    Path clone = GitHostFixture.clone(gitBase, repoId);
    GitHostFixture.git(clone, "git", "checkout", "-q", "-b", "a");
    GitHostFixture.commitFile(clone, "a.txt", "a\n", "a");
    GitHostFixture.git(clone, "git", "checkout", "-q", "main");
    GitHostFixture.git(clone, "git", "checkout", "-q", "-b", "b");
    GitHostFixture.commitFile(clone, "b.txt", "b\n", "b");
    GitHostFixture.git(clone, "git", "push", "-q", "origin", "a", "b");
    forgetPreviousPublishes();

    Map<String, Object> body = new LinkedHashMap<>();
    body.put("target", "refs/heads/release/1");
    body.put("sources", List.of("refs/heads/a", "refs/heads/b"));
    body.put("projectId", "p");
    body.put("repoName", "n");
    String merged =
        post(repoId, "/merges", body)
            .then()
            .statusCode(200)
            .extract()
            .path("sha");

    OutboxEvent published = only("SCMPublishCommit");
    assertTrue(published.payload.contains("\"branch\":\"release/1\""), published.payload);
    assertTrue(published.payload.contains("\"oldSha\":\"" + "0".repeat(40) + "\""), published.payload);
    assertTrue(published.payload.contains("\"sha\":\"" + merged + "\""), published.payload);
  }

  @Test
  public void aMergeThatMovedNothingAnnouncesNothing() throws Exception {
    // Re-running a fold is free by design — the "unchanged" answer — and a ref that did not move is
    // not a ref move. Announcing it would have every consumer react to a push nobody made.
    String repoId = GitHostFixture.seedOrigin(repositories, gitBase);
    forgetPreviousPublishes();

    Map<String, Object> body = new LinkedHashMap<>();
    body.put("target", "refs/heads/main");
    body.put("sources", List.of("refs/heads/main"));
    body.put("projectId", "p");
    body.put("repoName", "n");
    post(repoId, "/merges", body)
        .then()
        .statusCode(200)
        .body("outcome", is("unchanged"));

    assertEquals(List.of(), rows(), "an unchanged merge publishes nothing");
  }

  @Test
  public void aCommitThroughTheDoorAnnouncesTheBranch() throws Exception {
    String repoId = GitHostFixture.seedOrigin(repositories, gitBase);
    String before = GitHostFixture.requireRemoteRefSha(gitBase, repoId, "refs/heads/main");
    forgetPreviousPublishes();

    Map<String, Object> body = new LinkedHashMap<>();
    body.put("ref", "refs/heads/main");
    body.put("message", "stamp the version");
    body.put("files", Map.of("VERSION", "2026.1004.1\n"));
    body.put("projectId", "p");
    body.put("repoName", "n");
    String committed =
        post(repoId, "/commits", body)
            .then()
            .statusCode(200)
            .extract()
            .path("sha");

    OutboxEvent published = only("SCMPublishCommit");
    assertTrue(published.payload.contains("\"branch\":\"main\""), published.payload);
    assertTrue(published.payload.contains("\"oldSha\":\"" + before + "\""), published.payload);
    assertTrue(published.payload.contains("\"sha\":\"" + committed + "\""), published.payload);
    assertTrue(published.payload.contains("\"message\":\"stamp the version\\n\""), published.payload);
    assertTrue(published.payload.contains("\"projectId\":\"p\""), published.payload);
    assertTrue(published.payload.contains("\"repoName\":\"n\""), published.payload);

    // The same edit again builds the tip's own tree: "unchanged", and nothing more is said.
    forgetPreviousPublishes();
    post(repoId, "/commits", body)
        .then()
        .statusCode(200)
        .body("outcome", is("unchanged"));
    assertEquals(List.of(), rows(), "an unchanged commit publishes nothing");
  }

  @Test
  public void aTagThroughTheDoorAnnouncesTheTag() throws Exception {
    String repoId = GitHostFixture.seedOrigin(repositories, gitBase);
    String head = GitHostFixture.requireRemoteRefSha(gitBase, repoId, "refs/heads/main");
    forgetPreviousPublishes();

    Map<String, Object> body = new LinkedHashMap<>();
    body.put("name", "2026.1004.1");
    body.put("sha", "refs/heads/main");
    body.put("message", "released");
    body.put("projectId", "p");
    body.put("repoName", "n");
    String tagObject =
        post(repoId, "/tags", body)
            .then()
            .statusCode(201)
            .extract()
            .path("sha");

    OutboxEvent tag = only("SCMPublishTag");
    assertTrue(tag.payload.contains("\"tagName\":\"2026.1004.1\""), tag.payload);
    assertTrue(tag.payload.contains("\"annotated\":true"), tag.payload);
    assertTrue(tag.payload.contains("\"sha\":\"" + tagObject + "\""), tag.payload);
    assertTrue(tag.payload.contains("\"targetSha\":\"" + head + "\""), tag.payload);
    assertTrue(tag.payload.contains("\"projectId\":\"p\""), tag.payload);
    assertTrue(tag.payload.contains("\"repoName\":\"n\""), tag.payload);
    assertEquals(1, rows().size(), "a tag publishes the tag and nothing else");
  }

  @Test
  public void aBranchDeletedThroughTheDoorAnnouncesTheDeletion() throws Exception {
    // A release deletes the branches it consumed through this door; qits-maintenance ends a bump's
    // branch row on hearing it. The address rides the query string, because a DELETE has no body.
    // The branch is one of qits-maintenance's own automation branches (qits-1133): that is the one
    // shape left reachable by a push at all, and this test is about the REST delete door, not
    // about which maintenance branches RefScopeHook admits.
    String branch = "maintenance/automations/dependency-bump/req-1";
    String repoId = GitHostFixture.seedOrigin(repositories, gitBase);
    Path clone = GitHostFixture.clone(gitBase, repoId);
    GitHostFixture.git(clone, "git", "checkout", "-q", "-b", branch);
    GitHostFixture.commitFile(clone, "bump.txt", "bumped\n", "bump");
    String tip = GitHostFixture.head(clone);
    // Only a bump run may push a maintenance branch (RefScopeHook); the REST delete is not a push.
    GitHostFixture.gitAs(
        TestTokenMechanism.token(
            "{\"sub\":\"dyn-ci-run-1\",\"groups\":[\"qits:ci-run\"],\"context_kind\":\"ci-run\","
                + "\"git_refs\":[\"refs/heads/" + branch + "\"]}"),
        clone,
        "git",
        "push",
        "-q",
        "origin",
        branch);
    forgetPreviousPublishes();

    given()
        .queryParam("projectId", "p")
        .queryParam("repoName", "n")
        .when()
        .delete(API + repoId + "/branches/" + branch)
        .then()
        .statusCode(204);

    OutboxEvent deleted = only("SCMDeleteBranch");
    assertTrue(deleted.payload.contains("\"branch\":\"" + branch + "\""), deleted.payload);
    assertTrue(deleted.payload.contains("\"sha\":\"" + tip + "\""), deleted.payload);
    assertTrue(deleted.payload.contains("\"projectId\":\"p\""), deleted.payload);
    assertTrue(deleted.payload.contains("\"repoName\":\"n\""), deleted.payload);
    assertEquals(1, rows().size(), "a delete publishes a deletion and nothing else");
  }

  @Test
  public void aCallerThatNamesNoAddressIsStillServedAndAnnouncedWithoutOne() throws Exception {
    // The older callers, which predate the address: still served, and their moves announced the way
    // an id-addressed push is — with both name keys simply absent.
    String repoId = GitHostFixture.seedOrigin(repositories, gitBase);
    Path clone = GitHostFixture.clone(gitBase, repoId);
    GitHostFixture.git(clone, "git", "checkout", "-q", "-b", "old/caller");
    GitHostFixture.commitFile(clone, "old.txt", "old\n", "old");
    GitHostFixture.git(clone, "git", "push", "-q", "origin", "old/caller");
    forgetPreviousPublishes();

    Map<String, Object> body = new LinkedHashMap<>();
    body.put("target", "refs/heads/main");
    body.put("sources", List.of("refs/heads/old/caller"));
    post(repoId, "/merges", body).then().statusCode(200);
    given().when().delete(API + repoId + "/branches/old/caller").then().statusCode(204);

    OutboxEvent merged = only("SCMPublishCommit");
    OutboxEvent deleted = only("SCMDeleteBranch");
    for (OutboxEvent row : List.of(merged, deleted)) {
      assertFalse(row.payload.contains("projectId"), row.payload);
      assertFalse(row.payload.contains("repoName"), row.payload);
      assertTrue(row.payload.contains("\"repoId\":\"" + repoId + "\""), row.payload);
    }
  }

  @Test
  public void anAnnouncerThatThrowsNeverFailsTheDoor() throws Exception {
    // The ref has moved by the time anyone is told. A broken announcer must neither turn that into
    // an error for the caller nor keep the announcers beside it from hearing of the move.
    String repoId = GitHostFixture.seedOrigin(repositories, gitBase);
    forgetPreviousPublishes();

    Map<String, Object> body = new LinkedHashMap<>();
    body.put("ref", "refs/heads/main");
    body.put("message", "despite a broken announcer");
    body.put("files", Map.of("broken.txt", "still lands\n"));
    String committed;
    failingAnnouncer.arm();
    try {
      committed =
          post(repoId, "/commits", body)
              .then()
              .statusCode(200)
              .extract()
              .path("sha");
    } finally {
      failingAnnouncer.disarm();
    }

    assertEquals(
        committed, GitHostFixture.requireRemoteRefSha(gitBase, repoId, "refs/heads/main"));
    OutboxEvent published = only("SCMPublishCommit");
    assertTrue(published.payload.contains("\"sha\":\"" + committed + "\""), published.payload);
  }

  private io.restassured.response.Response post(String repoId, String path, Object body) {
    return given().contentType(ContentType.JSON).body(body).when().post(API + repoId + path);
  }

  /** Every outbox row, for the cases that assert on what a push did NOT publish. */
  private List<OutboxEvent> rows() {
    return QuarkusTransaction.requiringNew()
        .call(
            () ->
                outbox
                    .createQuery("select o from OutboxEvent o", OutboxEvent.class)
                    .getResultList());
  }

  /** The one outbox row of that event type, failing the test if there is not exactly one. */
  private OutboxEvent only(String name) {
    List<OutboxEvent> matching = rows().stream().filter(row -> name.equals(row.name)).toList();
    assertEquals(
        1, matching.size(), () -> "expected one " + name + " row, got " + matching.size());
    return matching.get(0);
  }
}
