package eu.wohlben.qits.githost.api;

import eu.wohlben.qits.githost.TestTokenMechanism;

import static io.restassured.RestAssured.given;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;

import eu.wohlben.qits.githost.GitRepositoryProvider;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.path.json.JsonPath;
import jakarta.inject.Inject;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * {@code POST /githost/api/repositories/{repoId}/merges} — the in-core octopus.
 *
 * <p>Seeded through the served git endpoint like the rest of this suite: receive-pack is the only
 * other door this storage has, and a DFS repository has no directory to build a fixture in. The
 * git helpers are re-spelled here rather than shared, the trade {@code RepositoryBrowseResourceTest}
 * documents for the same reason ({@code GitHostFixture} is package-private to the git-route tests).
 *
 * <p>Everything is asserted through the API and the wire — the merge response says what the ref now
 * is, the browse endpoint beside it says what the merged tree holds. Nothing here opens a
 * repository in-process, so nothing here can pass on a merge the served host would not answer.
 */
@QuarkusTest
public class RepositoryMergeResourceTest {

  static final String API = "/githost/api/repositories/";

  @Inject GitRepositoryProvider repositories;

  @TestHTTPResource("/git")
  URL gitBase;

  /** A repository with {@code main} and three branches, each adding one file of its own. */
  private String seedThreeBranches() throws Exception {
    String repoId = UUID.randomUUID().toString();
    repositories.create(repoId, "main");
    Path work = Files.createTempDirectory("qits-merge-seed");
    git(work, "init", "-q", "-b", "main", ".");
    write(work, "base.txt", "base\n");
    commit(work, "base");
    for (String branch : List.of("a", "b", "c")) {
      git(work, "checkout", "-q", "-b", "feature/" + branch, "main");
      write(work, branch + ".txt", branch + "\n");
      commit(work, branch);
    }
    git(work, "checkout", "-q", "main");
    push(work, repoId, "main", "feature/a", "feature/b", "feature/c");
    return repoId;
  }

  @Test
  public void threeSourcesFoldIntoOneCommitWithThreeParents() throws Exception {
    String repo = seedThreeBranches();
    JsonPath merged =
        merge(repo, request("refs/heads/release/1", "feature/a", "feature/b", "feature/c"))
            .then()
            .statusCode(200)
            .extract()
            .jsonPath();

    assertThat(merged.getString("outcome"), is("merged"));
    // One commit, three parents — a pairwise fold that ended in ONE octopus, not a chain of
    // two-parent merges.
    assertThat(merged.getList("parents", String.class), hasSize(3));
    // And the fold really folded: every branch's file is in the merged tree.
    List<String> paths =
        given()
            .queryParam("rev", "release/1")
            .when()
            .get(API + repo + "/tree")
            .then()
            .statusCode(200)
            .extract()
            .jsonPath()
            .getList("paths", String.class);
    assertThat(paths, hasItem("a.txt"));
    assertThat(paths, hasItem("b.txt"));
    assertThat(paths, hasItem("c.txt"));
  }

  @Test
  public void theTargetsOwnTipIsTheFirstParentOfTheNextFold() throws Exception {
    // git octopus folds onto HEAD, and the target ref is what plays HEAD here: an existing target
    // is a head like any other, and the first one — so N sources over an existing target produce a
    // commit with N+1 parents.
    String repo = seedThreeBranches();
    String first =
        merge(repo, request("refs/heads/release/2", "feature/a", "feature/b", "feature/c"))
            .then()
            .statusCode(200)
            .extract()
            .path("sha");

    Path work = Files.createTempDirectory("qits-merge-more");
    git(work, "clone", "-q", gitBase + "/" + repo, ".");
    for (String branch : List.of("a", "b", "c")) {
      git(work, "checkout", "-q", "-B", "feature/" + branch, "origin/feature/" + branch);
      write(work, branch + "-more.txt", branch + "\n");
      commit(work, branch + " again");
    }
    push(work, repo, "feature/a", "feature/b", "feature/c");

    JsonPath second =
        merge(repo, request("refs/heads/release/2", "feature/a", "feature/b", "feature/c"))
            .then()
            .statusCode(200)
            .extract()
            .jsonPath();
    assertThat(second.getString("outcome"), is("merged"));
    assertThat(second.getList("parents", String.class), hasSize(4));
    assertThat(second.getList("parents", String.class).get(0), is(first));
  }

  @Test
  public void asecondIdenticalCallCreatesNoCommit() throws Exception {
    String repo = seedThreeBranches();
    Map<String, Object> body = request("refs/heads/release/3", "feature/a", "feature/b");

    JsonPath first = merge(repo, body).then().statusCode(200).extract().jsonPath();
    assertThat(first.getString("outcome"), is("merged"));

    JsonPath again = merge(repo, body).then().statusCode(200).extract().jsonPath();
    // The heads did not move, so every one of them is contained in the target's tip and drops out;
    // the target is the only head left and it already says the right thing. Same sha, no commit.
    assertThat(again.getString("outcome"), is("unchanged"));
    assertThat(again.getString("sha"), is(first.getString("sha")));
    // Ordering the sources differently is the same question and gets the same answer.
    JsonPath reordered =
        merge(repo, request("refs/heads/release/3", "feature/b", "feature/a"))
            .then()
            .statusCode(200)
            .extract()
            .jsonPath();
    assertThat(reordered.getString("outcome"), is("unchanged"));
    assertThat(reordered.getString("sha"), is(first.getString("sha")));
  }

  @Test
  public void aHeadAnotherHeadAlreadyContainsIsSkippedRatherThanMerged() throws Exception {
    // feature/a descends from main, so main is contained in it and contributes nothing — git's own
    // "Already up to date". One head is left, so the target is moved onto it and NO merge commit
    // with a single parent is written.
    String repo = seedThreeBranches();
    JsonPath answer =
        merge(repo, request("refs/heads/release/4", "refs/heads/main", "feature/a"))
            .then()
            .statusCode(200)
            .extract()
            .jsonPath();
    assertThat(answer.getString("outcome"), is("fast-forward"));
    assertThat(answer.getList("skipped", String.class), contains("refs/heads/main"));
    assertThat(answer.getString("sha"), is(remoteSha(repo, "refs/heads/feature/a")));
    assertThat(answer.getString("sha"), is(remoteSha(repo, "refs/heads/release/4")));

    // And asking again moves nothing: the target IS the surviving head now.
    JsonPath again =
        merge(repo, request("refs/heads/release/4", "refs/heads/main", "feature/a"))
            .then()
            .statusCode(200)
            .extract()
            .jsonPath();
    assertThat(again.getString("outcome"), is("unchanged"));
  }

  @Test
  public void aConflictIsReportedWithItsPathsAndItsHeadAndMovesNoRef() throws Exception {
    String repoId = UUID.randomUUID().toString();
    repositories.create(repoId, "main");
    Path work = Files.createTempDirectory("qits-merge-conflict");
    git(work, "init", "-q", "-b", "main", ".");
    write(work, "shared.txt", "base\n");
    write(work, "quiet.txt", "untouched\n");
    commit(work, "base");
    git(work, "checkout", "-q", "-b", "feature/left", "main");
    write(work, "shared.txt", "left\n");
    commit(work, "left");
    git(work, "checkout", "-q", "-b", "feature/right", "main");
    write(work, "shared.txt", "right\n");
    commit(work, "right");
    git(work, "checkout", "-q", "main");
    push(work, repoId, "main", "feature/left", "feature/right");

    JsonPath conflict =
        merge(repoId, request("refs/heads/release/5", "feature/left", "feature/right"))
            .then()
            .statusCode(409)
            .extract()
            .jsonPath();
    assertThat(conflict.getString("error"), is("merge-conflict"));
    assertThat(conflict.getString("target"), is("refs/heads/release/5"));
    assertThat(conflict.getList("conflicts.path", String.class), contains("shared.txt"));
    // The head being folded in when it broke, spelled as the caller spelled it.
    assertThat(conflict.getList("conflicts.head", String.class), contains("feature/right"));
    assertThat(
        conflict.getList("conflicts.headSha", String.class),
        contains(remoteSha(repoId, "refs/heads/feature/right")));
    // No ref moved, and nothing was left behind for the caller to clean up.
    assertThat(remoteSha(repoId, "refs/heads/release/5"), is(nullSha()));
  }

  @Test
  public void aGitlinkConflictSaysSoAndNamesWhatAllThreeSidesPin() throws Exception {
    // The conflict a caller CAN decide without a worktree, and the reason the report grew: four flat
    // strings could not tell a submodule pin from a text file, so qits-projects had to guess.
    String repo = seedGitlinkConflict();
    JsonPath conflict =
        merge(repo, request("refs/heads/release/g1", "feature/left", "feature/right"))
            .then()
            .statusCode(409)
            .extract()
            .jsonPath();
    assertThat(conflict.getList("conflicts.path", String.class), contains("sub"));
    assertThat(conflict.getList("conflicts.kind", String.class), contains("gitlink"));
    assertThat(conflict.getList("conflicts.reason", String.class), contains("content"));
    // Commits of the SUBMODULE's repository, forwarded as values — nothing here resolves them.
    assertThat(conflict.getList("conflicts.base", String.class), contains(PIN_BASE));
    assertThat(conflict.getList("conflicts.ours", String.class), contains(PIN_LEFT));
    assertThat(conflict.getList("conflicts.theirs", String.class), contains(PIN_RIGHT));
    assertThat(remoteSha(repo, "refs/heads/release/g1"), is(nullSha()));
  }

  @Test
  public void aTextConflictIsAFileAndKeepsTheFourOldFields() throws Exception {
    // The compatibility half of the same change: qits-projects has conflict JSON in its database and
    // a frontend reading exactly these four names, so they say what they have always said.
    String repo = seedTextConflict();
    JsonPath conflict =
        merge(repo, request("refs/heads/release/g2", "feature/left", "feature/right"))
            .then()
            .statusCode(409)
            .extract()
            .jsonPath();
    assertThat(conflict.getList("conflicts.path", String.class), contains("shared.txt"));
    assertThat(conflict.getList("conflicts.head", String.class), contains("feature/right"));
    assertThat(
        conflict.getList("conflicts.headSha", String.class),
        contains(remoteSha(repo, "refs/heads/feature/right")));
    assertThat(conflict.getList("conflicts.reason", String.class), contains("content"));
    assertThat(conflict.getList("conflicts.kind", String.class), contains("file"));
    // A blob id on each side, and nothing that looks like a pin.
    assertThat(conflict.getString("conflicts[0].ours"), is(not(conflict.getString("conflicts[0].theirs"))));
  }

  @Test
  public void aDirectedGitlinkConflictFoldsAndKeepsTheRealHeadsAsParents() throws Exception {
    String repo = seedGitlinkConflict();
    JsonPath merged =
        merge(repo, directed(request("refs/heads/release/g3", "feature/left", "feature/right"), "sub", PIN_DIRECTED))
            .then()
            .statusCode(200)
            .extract()
            .jsonPath();
    assertThat(merged.getString("outcome"), is("merged"));
    assertThat(merged.getList("resolved", String.class), contains("sub"));

    // The property most worth pinning down: the commit that was published names the SOURCES as its
    // parents. The rewritings the resolution needed are throwaways and must not appear here — one of
    // them would be a forged history, and on a later octopus step it would be a sha that resolves
    // nowhere at all.
    String left = remoteSha(repo, "refs/heads/feature/left");
    String right = remoteSha(repo, "refs/heads/feature/right");
    assertThat(merged.getList("parents", String.class), contains(left, right));
    Path clone = clone(repo);
    assertThat(parentsOf(clone, merged.getString("sha")), contains(left, right));
    // And the tree holds what was directed, as a gitlink and not as a file.
    assertThat(
        treeEntry(clone, merged.getString("sha"), "sub"),
        startsWith("160000 commit " + PIN_DIRECTED));
  }

  @Test
  public void aDirectiveOnAPathThatDidNotConflictIsRefused() throws Exception {
    // A resolution that decided nothing is a caller's typo, and answering 200 to it would let a
    // release request believe it resolved something it did not.
    String repo = seedThreeBranches();
    merge(repo, directed(request("refs/heads/release/g4", "feature/a", "feature/b"), "sub", PIN_DIRECTED))
        .then()
        .statusCode(400)
        .body("error", is("bad-request"));
    assertThat(remoteSha(repo, "refs/heads/release/g4"), is(nullSha()));
    // Same for a fold that never merged anything at all.
    merge(repo, directed(request("refs/heads/release/g4b", "refs/heads/main"), "sub", PIN_DIRECTED))
        .then()
        .statusCode(400);
    assertThat(remoteSha(repo, "refs/heads/release/g4b"), is(nullSha()));
  }

  @Test
  public void aDirectiveOnAConflictThatIsNotAGitlinkIsRefused() throws Exception {
    // The security property of the whole field: it decides a submodule pin git could not decide, and
    // never becomes a door for writing a file the merge happened to argue about.
    String repo = seedTextConflict();
    merge(
            repo,
            directed(
                request("refs/heads/release/g5", "feature/left", "feature/right"),
                "shared.txt",
                PIN_DIRECTED))
        .then()
        .statusCode(400)
        .body("error", is("bad-request"));
    assertThat(remoteSha(repo, "refs/heads/release/g5"), is(nullSha()));
    // And the shape rules of the pin itself, which are the commit endpoint's rules.
    merge(
            repo,
            directed(
                request("refs/heads/release/g5", "feature/left", "feature/right"), "sub", "nope"))
        .then()
        .statusCode(400);
    merge(
            repo,
            directed(
                request("refs/heads/release/g5", "feature/left", "feature/right"),
                "../escape",
                PIN_DIRECTED))
        .then()
        .statusCode(400);
  }

  @Test
  public void aPartlyDirectedFoldIsAConflictRatherThanHalfAMerge() throws Exception {
    String repo = seedGitlinkAndTextConflict();
    JsonPath conflict =
        merge(
                repo,
                directed(
                    request("refs/heads/release/g6", "feature/left", "feature/right"),
                    "sub",
                    PIN_DIRECTED))
            .then()
            .statusCode(409)
            .extract()
            .jsonPath();
    // The residue — what is LEFT to argue about — rather than the path the caller already decided.
    assertThat(conflict.getList("conflicts.path", String.class), contains("shared.txt"));
    assertThat(conflict.getList("conflicts.kind", String.class), contains("file"));
    assertThat(remoteSha(repo, "refs/heads/release/g6"), is(nullSha()));
  }

  @Test
  public void anEmptyResolutionsListIsTheOldFoldExactly() throws Exception {
    String clean = seedThreeBranches();
    Map<String, Object> body = request("refs/heads/release/g7", "feature/a", "feature/b");
    body.put("resolutions", List.of());
    JsonPath merged = merge(clean, body).then().statusCode(200).extract().jsonPath();
    assertThat(merged.getString("outcome"), is("merged"));
    assertThat(merged.getList("resolved", String.class), hasSize(0));

    String conflicting = seedGitlinkConflict();
    Map<String, Object> conflicted =
        request("refs/heads/release/g7", "feature/left", "feature/right");
    conflicted.put("resolutions", List.of());
    merge(conflicting, conflicted)
        .then()
        .statusCode(409)
        .body("conflicts.path", contains("sub"))
        .body("conflicts.kind", contains("gitlink"));
  }

  @Test
  public void aConflictOnALaterOctopusStepNeverNamesAnAccumulator() throws Exception {
    // From the third head on, the accumulator is a throwaway commit living in an unflushed inserter:
    // on a conflict that inserter is discarded and its sha resolves nowhere, so nothing derived from
    // it may reach the wire. Per-path tree entries are plain values and do not have that problem.
    String repo = seedLaterStepGitlinkConflict();
    JsonPath conflict =
        merge(repo, request("refs/heads/release/g8", "feature/a", "feature/left", "feature/right"))
            .then()
            .statusCode(409)
            .extract()
            .jsonPath();
    assertThat(conflict.getList("conflicts.path", String.class), contains("sub"));
    assertThat(conflict.getList("conflicts.kind", String.class), contains("gitlink"));
    // The head that broke it is the real source, not the accumulator it was folded onto.
    assertThat(
        conflict.getList("conflicts.headSha", String.class),
        contains(remoteSha(repo, "refs/heads/feature/right")));
    assertThat(conflict.getList("conflicts.head", String.class), contains("feature/right"));
    // What "ours" holds is the pin the accumulated tree carries — a submodule's commit — and every
    // side is one of the three pins this fixture wrote.
    assertThat(conflict.getList("conflicts.base", String.class), contains(PIN_BASE));
    assertThat(conflict.getList("conflicts.ours", String.class), contains(PIN_LEFT));
    assertThat(conflict.getList("conflicts.theirs", String.class), contains(PIN_RIGHT));

    JsonPath merged =
        merge(
                repo,
                directed(
                    request("refs/heads/release/g8", "feature/a", "feature/left", "feature/right"),
                    "sub",
                    PIN_DIRECTED))
            .then()
            .statusCode(200)
            .extract()
            .jsonPath();
    assertThat(merged.getString("outcome"), is("merged"));
    assertThat(merged.getList("resolved", String.class), contains("sub"));
    List<String> heads =
        List.of(
            remoteSha(repo, "refs/heads/feature/a"),
            remoteSha(repo, "refs/heads/feature/left"),
            remoteSha(repo, "refs/heads/feature/right"));
    assertThat(merged.getList("parents", String.class), is(heads));
    Path clone = clone(repo);
    assertThat(parentsOf(clone, merged.getString("sha")), is(heads));
    assertThat(
        treeEntry(clone, merged.getString("sha"), "sub"),
        startsWith("160000 commit " + PIN_DIRECTED));
  }

  // --- the version-pin rule ------------------------------------------------------------------

  /** The pom the version-pin tests fold, with the two bumped properties as parameters. */
  private static String pom(String artifact, String protocol, String eventstream) {
    return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
        + "<project>\n"
        + "  <artifactId>" + artifact + "</artifactId>\n"
        + "  <properties>\n"
        + "    <qits.workspace-daemon-protocol.version>" + protocol
        + "</qits.workspace-daemon-protocol.version>\n"
        + "    <java.version>21</java.version>\n"
        + "    <maven.compiler.release>21</maven.compiler.release>\n"
        + "    <qits.eventstream.version>" + eventstream + "</qits.eventstream.version>\n"
        + "  </properties>\n"
        + "</project>\n";
  }

  /** {@code main}, {@code feature/left} and {@code feature/right}, each writing one file differently. */
  private String seedFileConflict(String path, String base, String left, String right)
      throws Exception {
    String repoId = UUID.randomUUID().toString();
    repositories.create(repoId, "main");
    Path work = Files.createTempDirectory("qits-merge-pins");
    git(work, "init", "-q", "-b", "main", ".");
    write(work, path, base);
    commit(work, "base");
    git(work, "checkout", "-q", "-b", "feature/left", "main");
    write(work, path, left);
    commit(work, "left");
    git(work, "checkout", "-q", "-b", "feature/right", "main");
    write(work, path, right);
    commit(work, "right");
    git(work, "checkout", "-q", "main");
    push(work, repoId, "main", "feature/left", "feature/right");
    return repoId;
  }

  /** The same body, asking for the version-pin rule. */
  private static Map<String, Object> pinned(Map<String, Object> body) {
    body.put("versionPins", true);
    return body;
  }

  /** A blob's exact content at a revision, read from a clone of what the host wrote. */
  private static String blob(Path clone, String rev, String path) throws Exception {
    return git(clone, "cat-file", "blob", rev + ":" + path);
  }

  @Test
  public void twoBumpsOfTheSameVersionsInAPomFoldToTheNewerOnes() throws Exception {
    // The fold that motivated the rule: two released sources each bumped the protocol version, and
    // the answer is the newer one. A second property shows a pair is compared numerically — 1.4.10
    // beats 1.4.2 although it sorts lower as text — and that "ours" can be the side that wins.
    String repo =
        seedFileConflict(
            "pom.xml",
            pom("demo", "2026.1005.191755", "1.4.0"),
            pom("demo", "2026.1006.55511", "1.4.10"),
            pom("demo-app", "2026.1006.64435", "1.4.2"));
    JsonPath merged =
        merge(repo, pinned(request("refs/heads/release/v1", "feature/left", "feature/right")))
            .then()
            .statusCode(200)
            .extract()
            .jsonPath();
    assertThat(merged.getString("outcome"), is("merged"));
    assertThat(merged.getList("resolved", String.class), contains("pom.xml"));
    assertThat(merged.getList("resolvedVersions.path", String.class), contains("pom.xml", "pom.xml"));
    assertThat(merged.getList("resolvedVersions.line", Integer.class), contains(5, 8));
    assertThat(
        merged.getList("resolvedVersions.ours", String.class),
        contains("2026.1006.55511", "1.4.10"));
    assertThat(
        merged.getList("resolvedVersions.theirs", String.class),
        contains("2026.1006.64435", "1.4.2"));
    assertThat(
        merged.getList("resolvedVersions.chosen", String.class),
        contains("2026.1006.64435", "1.4.10"));

    // The ref moved to the answer, the parents are the real sources, and the tree is the merge —
    // the clean change on the right included — byte for byte, final newline and all.
    assertThat(remoteSha(repo, "refs/heads/release/v1"), is(merged.getString("sha")));
    List<String> heads =
        List.of(remoteSha(repo, "refs/heads/feature/left"), remoteSha(repo, "refs/heads/feature/right"));
    assertThat(merged.getList("parents", String.class), is(heads));
    Path clone = clone(repo);
    assertThat(parentsOf(clone, merged.getString("sha")), is(heads));
    assertThat(
        blob(clone, merged.getString("sha"), "pom.xml"),
        is(pom("demo-app", "2026.1006.64435", "1.4.10")));
    // And the decision is in the history, not only in the answer: one trailer per decided pair, in
    // resolvedVersions order, a blank line after the message the fold composed.
    assertThat(
        message(clone, merged.getString("sha")),
        is(
            "Merge feature/right into refs/heads/release/v1\n"
                + "\n"
                + "Resolved-Version: pom.xml:5 ours=2026.1006.55511 theirs=2026.1006.64435"
                + " -> 2026.1006.64435\n"
                + "Resolved-Version: pom.xml:8 ours=1.4.10 theirs=1.4.2 -> 1.4.10\n"));
  }

  @Test
  public void versionTrailersJoinACallersOwnTrailerBlock() throws Exception {
    String repo =
        seedFileConflict(
            "pom.xml",
            pom("demo", "1.0.0", "1.4.0"),
            pom("demo", "1.1.0", "1.4.0"),
            pom("demo", "1.0.1", "1.4.0"));
    Map<String, Object> body =
        pinned(request("refs/heads/release/v10", "feature/left", "feature/right"));
    body.put("message", "Release request 17\n\nfolds two sources\n\nRelease-Request: 17\n");
    String sha =
        merge(repo, body).then().statusCode(200).extract().path("sha");
    assertThat(
        message(clone(repo), sha),
        is(
            "Release request 17\n\nfolds two sources\n\nRelease-Request: 17\n"
                + "Resolved-Version: pom.xml:5 ours=1.1.0 theirs=1.0.1 -> 1.1.0\n"));

    // A subject alone is not a trailer block, even when it looks like one.
    Map<String, Object> subject =
        pinned(request("refs/heads/release/v11", "feature/left", "feature/right"));
    subject.put("message", "fix: fold");
    String other = merge(repo, subject).then().statusCode(200).extract().path("sha");
    assertThat(
        message(clone(repo), other),
        is("fix: fold\n\nResolved-Version: pom.xml:5 ours=1.1.0 theirs=1.0.1 -> 1.1.0\n"));
  }

  @Test
  public void aFoldThatDecidedNoVersionWritesTheMessageUntouched() throws Exception {
    String repo = seedThreeBranches();
    Map<String, Object> body = pinned(request("refs/heads/release/v12", "feature/a", "feature/b"));
    body.put("message", "Plain fold\n\nRelease-Request: 18");
    String sha = merge(repo, body).then().statusCode(200).extract().path("sha");
    assertThat(message(clone(repo), sha), is("Plain fold\n\nRelease-Request: 18\n"));
  }

  /** A commit's raw message, as git stores it. */
  private static String message(Path clone, String sha) throws Exception {
    return git(clone, "cat-file", "commit", sha).split("\n\n", 2)[1];
  }

  @Test
  public void componentsAreComparedAsNumbersWhateverTheirLength() throws Exception {
    // 919 is shorter than 1006 and sorts after it as text; the release scheme's day-of-year
    // component is variable-length, so only a numeric comparison gets it right. CRLF endings and a
    // missing final newline ride along to show the file's own bytes are kept.
    String base = "<project>\r\n  <version>2026.901.1</version>\r\n</project>";
    String left = "<project>\r\n  <version>2026.1006.5</version>\r\n</project>";
    String right = "<project>\r\n  <version>2026.919.120127</version>\r\n</project>";
    String repo = seedFileConflict("sub/dir/pom.xml", base, left, right);
    JsonPath merged =
        merge(repo, pinned(request("refs/heads/release/v2", "feature/left", "feature/right")))
            .then()
            .statusCode(200)
            .extract()
            .jsonPath();
    assertThat(merged.getList("resolved", String.class), contains("sub/dir/pom.xml"));
    assertThat(merged.getList("resolvedVersions.chosen", String.class), contains("2026.1006.5"));
    assertThat(merged.getList("resolvedVersions.line", Integer.class), contains(2));
    assertThat(blob(clone(repo), merged.getString("sha"), "sub/dir/pom.xml"), is(left));
  }

  @Test
  public void aVersionBumpBesideAnyOtherChangeIsStillAConflict() throws Exception {
    String base = "<project>\n  <!-- pinned -->\n  <version>1.2.0</version>\n</project>\n";
    String left = "<project>\n  <!-- pinned for the bug -->\n  <version>1.3.0</version>\n</project>\n";
    String right = "<project>\n  <!-- pinned -->\n  <version>1.4.0</version>\n</project>\n";
    String repo = seedFileConflict("pom.xml", base, left, right);
    merge(repo, pinned(request("refs/heads/release/v3", "feature/left", "feature/right")))
        .then()
        .statusCode(409)
        .body("conflicts.path", contains("pom.xml"))
        .body("conflicts.kind", contains("file"));
    assertThat(remoteSha(repo, "refs/heads/release/v3"), is(nullSha()));
  }

  @Test
  public void aPackageJsonRangeIsDecidedOnlyWhenItsOperatorAgrees() throws Exception {
    String base = "{\n  \"dependencies\": {\n    \"@qits/x\": \"^1.2.3\"\n  }\n}\n";
    String left = "{\n  \"dependencies\": {\n    \"@qits/x\": \"^1.3.0\"\n  }\n}\n";
    String same = "{\n  \"dependencies\": {\n    \"@qits/x\": \"^1.2.9\"\n  }\n}\n";
    String tilde = "{\n  \"dependencies\": {\n    \"@qits/x\": \"~1.2.9\"\n  }\n}\n";

    String agreeing = seedFileConflict("package.json", base, left, same);
    JsonPath merged =
        merge(agreeing, pinned(request("refs/heads/release/v4", "feature/left", "feature/right")))
            .then()
            .statusCode(200)
            .extract()
            .jsonPath();
    assertThat(merged.getList("resolvedVersions.chosen", String.class), contains("1.3.0"));
    assertThat(blob(clone(agreeing), merged.getString("sha"), "package.json"), is(left));

    String disagreeing = seedFileConflict("package.json", base, left, tilde);
    merge(disagreeing, pinned(request("refs/heads/release/v4", "feature/left", "feature/right")))
        .then()
        .statusCode(409)
        .body("conflicts.path", contains("package.json"));
    assertThat(remoteSha(disagreeing, "refs/heads/release/v4"), is(nullSha()));
  }

  @Test
  public void aQualifierMismatchOrAnUnorderablePairIsAConflict() throws Exception {
    String base = "<project>\n  <version>1.2.0</version>\n</project>\n";
    String snapshot = seedFileConflict(
        "pom.xml",
        base,
        "<project>\n  <version>1.3.0-SNAPSHOT</version>\n</project>\n",
        "<project>\n  <version>1.3.0</version>\n</project>\n");
    merge(snapshot, pinned(request("refs/heads/release/v5", "feature/left", "feature/right")))
        .then()
        .statusCode(409)
        .body("conflicts.path", contains("pom.xml"));

    // The same value spelled two ways has no newer side.
    String spelled = seedFileConflict(
        "pom.xml",
        base,
        "<project>\n  <version>1.3</version>\n</project>\n",
        "<project>\n  <version>1.3.0</version>\n</project>\n");
    merge(spelled, pinned(request("refs/heads/release/v5", "feature/left", "feature/right")))
        .then()
        .statusCode(409);
    assertThat(remoteSha(spelled, "refs/heads/release/v5"), is(nullSha()));
  }

  @Test
  public void onlyAPomOrAPackageJsonIsDecided() throws Exception {
    String repo =
        seedFileConflict("versions.txt", "x 1.0.0\n", "x 1.1.0\n", "x 1.2.0\n");
    merge(repo, pinned(request("refs/heads/release/v6", "feature/left", "feature/right")))
        .then()
        .statusCode(409)
        .body("conflicts.path", contains("versions.txt"));
    String lock =
        seedFileConflict(
            "package-lock.json",
            "{\"version\": \"1.0.0\"}\n",
            "{\"version\": \"1.1.0\"}\n",
            "{\"version\": \"1.2.0\"}\n");
    merge(lock, pinned(request("refs/heads/release/v6", "feature/left", "feature/right")))
        .then()
        .statusCode(409)
        .body("conflicts.path", contains("package-lock.json"));
  }

  @Test
  public void withoutTheFlagAVersionBumpIsTheConflictItAlwaysWas() throws Exception {
    String repo =
        seedFileConflict(
            "pom.xml",
            pom("demo", "2026.1005.191755", "1.4.0"),
            pom("demo", "2026.1006.55511", "1.4.0"),
            pom("demo", "2026.1006.64435", "1.4.0"));
    merge(repo, request("refs/heads/release/v7", "feature/left", "feature/right"))
        .then()
        .statusCode(409)
        .body("conflicts.path", contains("pom.xml"));
    Map<String, Object> off = request("refs/heads/release/v7", "feature/left", "feature/right");
    off.put("versionPins", false);
    merge(repo, off).then().statusCode(409);
    assertThat(remoteSha(repo, "refs/heads/release/v7"), is(nullSha()));
  }

  @Test
  public void aVersionPinAndAGitlinkDirectiveDecideOneFoldTogether() throws Exception {
    String repoId = UUID.randomUUID().toString();
    Path work = seedGitlinkBase(repoId);
    write(work, "pom.xml", pom("demo", "1.0.0", "1.4.0"));
    git(work, "add", "pom.xml");
    commitIndex(work, "pom");
    git(work, "checkout", "-q", "-b", "feature/left", "main");
    write(work, "pom.xml", pom("demo", "1.1.0", "1.4.0"));
    git(work, "add", "pom.xml");
    pin(work, "sub", PIN_LEFT);
    commitIndex(work, "left");
    git(work, "checkout", "-q", "-b", "feature/right", "main");
    write(work, "pom.xml", pom("demo", "1.0.1", "1.4.0"));
    git(work, "add", "pom.xml");
    pin(work, "sub", PIN_RIGHT);
    commitIndex(work, "right");
    git(work, "checkout", "-q", "main");
    push(work, repoId, "main", "feature/left", "feature/right");

    // The rule alone leaves the pin, and the 409 names only that.
    merge(repoId, pinned(request("refs/heads/release/v8", "feature/left", "feature/right")))
        .then()
        .statusCode(409)
        .body("conflicts.path", contains("sub"))
        .body("conflicts.kind", contains("gitlink"));
    // The directive alone leaves the pom.
    merge(
            repoId,
            directed(request("refs/heads/release/v8", "feature/left", "feature/right"), "sub", PIN_DIRECTED))
        .then()
        .statusCode(409)
        .body("conflicts.path", contains("pom.xml"));
    assertThat(remoteSha(repoId, "refs/heads/release/v8"), is(nullSha()));

    JsonPath merged =
        merge(
                repoId,
                pinned(
                    directed(
                        request("refs/heads/release/v8", "feature/left", "feature/right"),
                        "sub",
                        PIN_DIRECTED)))
            .then()
            .statusCode(200)
            .extract()
            .jsonPath();
    assertThat(merged.getList("resolved", String.class), contains("pom.xml", "sub"));
    assertThat(merged.getList("resolvedVersions.chosen", String.class), contains("1.1.0"));
    Path clone = clone(repoId);
    assertThat(blob(clone, merged.getString("sha"), "pom.xml"), is(pom("demo", "1.1.0", "1.4.0")));
    assertThat(
        treeEntry(clone, merged.getString("sha"), "sub"),
        startsWith("160000 commit " + PIN_DIRECTED));
  }

  @Test
  public void aBumpThatOnlyConflictsOnTheSecondOctopusStepIsDecidedThere() throws Exception {
    // feature/a folds in cleanly; the pom only conflicts when feature/right meets the accumulator
    // that already carries feature/left's bump — so that step's three sides are what decide it.
    String repoId = UUID.randomUUID().toString();
    repositories.create(repoId, "main");
    Path work = Files.createTempDirectory("qits-merge-pins-octopus");
    git(work, "init", "-q", "-b", "main", ".");
    write(work, "pom.xml", pom("demo", "2026.1005.191755", "1.4.0"));
    commit(work, "base");
    git(work, "checkout", "-q", "-b", "feature/a", "main");
    write(work, "a.txt", "a\n");
    commit(work, "a");
    git(work, "checkout", "-q", "-b", "feature/left", "main");
    write(work, "pom.xml", pom("demo", "2026.1006.64435", "1.4.0"));
    commit(work, "left");
    git(work, "checkout", "-q", "-b", "feature/right", "main");
    write(work, "pom.xml", pom("demo", "2026.1006.55511", "1.5.0"));
    commit(work, "right");
    git(work, "checkout", "-q", "main");
    push(work, repoId, "main", "feature/a", "feature/left", "feature/right");

    JsonPath merged =
        merge(
                repoId,
                pinned(
                    request(
                        "refs/heads/release/v9", "feature/a", "feature/left", "feature/right")))
            .then()
            .statusCode(200)
            .extract()
            .jsonPath();
    assertThat(merged.getString("outcome"), is("merged"));
    assertThat(merged.getList("parents", String.class), hasSize(3));
    assertThat(merged.getList("resolved", String.class), contains("pom.xml"));
    // Only the protocol line conflicted; right's eventstream bump was a clean chunk and is merged
    // as git merged it. "ours" is the accumulator, which by then carries feature/left's bump.
    assertThat(merged.getList("resolvedVersions.ours", String.class), contains("2026.1006.64435"));
    assertThat(merged.getList("resolvedVersions.theirs", String.class), contains("2026.1006.55511"));
    assertThat(merged.getList("resolvedVersions.chosen", String.class), contains("2026.1006.64435"));
    Path clone = clone(repoId);
    assertThat(
        blob(clone, merged.getString("sha"), "pom.xml"),
        is(pom("demo", "2026.1006.64435", "1.5.0")));
    assertThat(blob(clone, merged.getString("sha"), "a.txt"), is("a\n"));
  }

  @Test
  public void twoBumpsOfADockerfileFromTagFoldToTheNewerOnes() throws Exception {
    // The pins qits-maintenance bumps in a Dockerfile are FROM tags. The registry's port is not one,
    // the -jre qualifier has to agree, and the stage name rides along untouched.
    String base =
        "FROM eclipse-temurin:21.0.4-jre AS build\n"
            + "WORKDIR /app\n"
            + "COPY . .\n"
            + "FROM registry.local:5000/qits/runtime:2026.1005.191755\n";
    String left =
        "FROM eclipse-temurin:21.0.10-jre AS build\n"
            + "WORKDIR /app\n"
            + "COPY . .\n"
            + "FROM registry.local:5000/qits/runtime:2026.1006.55511\n";
    String right =
        "FROM eclipse-temurin:21.0.6-jre AS build\n"
            + "WORKDIR /app\n"
            + "COPY . .\n"
            + "FROM registry.local:5000/qits/runtime:2026.1006.64435\n";
    String repo = seedFileConflict("images/runtime.Dockerfile", base, left, right);
    JsonPath merged =
        merge(repo, pinned(request("refs/heads/release/d1", "feature/left", "feature/right")))
            .then()
            .statusCode(200)
            .extract()
            .jsonPath();
    assertThat(merged.getList("resolved", String.class), contains("images/runtime.Dockerfile"));
    assertThat(merged.getList("resolvedVersions.line", Integer.class), contains(1, 4));
    assertThat(
        merged.getList("resolvedVersions.chosen", String.class),
        contains("21.0.10-jre", "2026.1006.64435"));
    assertThat(
        blob(clone(repo), merged.getString("sha"), "images/runtime.Dockerfile"),
        is(
            "FROM eclipse-temurin:21.0.10-jre AS build\n"
                + "WORKDIR /app\n"
                + "COPY . .\n"
                + "FROM registry.local:5000/qits/runtime:2026.1006.64435\n"));
  }

  @Test
  public void aDockerfileDigestIsNeverAVersion() throws Exception {
    String repo =
        seedFileConflict(
            "Dockerfile",
            "FROM alpine:3.19@sha256:" + "a".repeat(64) + "\n",
            "FROM alpine:3.20@sha256:" + "b".repeat(64) + "\n",
            "FROM alpine:3.21@sha256:" + "c".repeat(64) + "\n");
    merge(repo, pinned(request("refs/heads/release/d2", "feature/left", "feature/right")))
        .then()
        .statusCode(409)
        .body("conflicts.path", contains("Dockerfile"));
    assertThat(remoteSha(repo, "refs/heads/release/d2"), is(nullSha()));
  }

  @Test
  public void aDockerfileVersionOutsideAFromLineIsAConflict() throws Exception {
    // A pom would call 1.2.4 a version wherever it stood; a Dockerfile only reads FROM tags (and a
    // trivial ARG), so an ENV that two heads bumped is plain text and stays the caller's.
    String repo =
        seedFileConflict(
            "Dockerfile",
            "FROM alpine:3.19\nENV TOOL_VERSION=1.2.3\n",
            "FROM alpine:3.19\nENV TOOL_VERSION=1.2.4\n",
            "FROM alpine:3.19\nENV TOOL_VERSION=1.2.5\n");
    merge(repo, pinned(request("refs/heads/release/d3", "feature/left", "feature/right")))
        .then()
        .statusCode(409)
        .body("conflicts.path", contains("Dockerfile"));
    assertThat(remoteSha(repo, "refs/heads/release/d3"), is(nullSha()));
  }

  @Test
  public void containmentIsAnOrdinaryReadWithTwoOrdinaryAnswers() throws Exception {
    String repo = seedThreeBranches();
    String main = remoteSha(repo, "refs/heads/main");
    String a = remoteSha(repo, "refs/heads/feature/a");

    containment(repo, main, "feature/a").then().statusCode(200).body("contains", is(true));
    // false is an answer, never an error.
    containment(repo, a, "refs/heads/main").then().statusCode(200).body("contains", is(false));
    containment(repo, "feature/a", "feature/a")
        .then()
        .statusCode(200)
        .body("contains", is(true))
        .body("commit", is(a))
        .body("in", is(a))
        .body("repoId", is(repo));

    containment(repo, "0000000000000000000000000000000000000001", "refs/heads/main")
        .then()
        .statusCode(404)
        .body("error", is("no-such-commit"));
    containment(repo, "refs/heads/main", "feature/nope")
        .then()
        .statusCode(404)
        .body("error", is("no-such-commit"))
        .body("detail", is("feature/nope"));
    containment(UUID.randomUUID().toString(), "refs/heads/main", "refs/heads/main")
        .then()
        .statusCode(404)
        .body("error", is("no-such-repository"));
    containment(repo, "main^{tree}", "refs/heads/main").then().statusCode(400);
    given().when().get(API + repo + "/contains").then().statusCode(400);
  }

  @Test
  public void oneSourceOnlyCreatesTheTargetRatherThanAnEmptyOctopus() throws Exception {
    String repo = seedThreeBranches();
    JsonPath answer =
        merge(repo, request("refs/heads/release/6", "refs/heads/main"))
            .then()
            .statusCode(200)
            .extract()
            .jsonPath();
    assertThat(answer.getString("outcome"), is("fast-forward"));
    assertThat(answer.getString("sha"), is(remoteSha(repo, "refs/heads/main")));
    assertThat(answer.getList("parents", String.class), not(hasItem(answer.getString("sha"))));
  }

  @Test
  public void whatTheHostCannotResolveIsNamedRatherThanGuessed() throws Exception {
    String repo = seedThreeBranches();
    merge(UUID.randomUUID().toString(), request("refs/heads/release/7", "refs/heads/main"))
        .then()
        .statusCode(404)
        .body("error", is("no-such-repository"));
    merge(repo, request("refs/heads/release/7", "feature/nope"))
        .then()
        .statusCode(404)
        .body("error", is("no-such-source"));
    merge(repo, request("release/7", "refs/heads/main")).then().statusCode(400);
    merge(repo, request("refs/tags/v1", "refs/heads/main")).then().statusCode(400);
    merge(repo, request("refs/heads/release/7")).then().statusCode(400);
    merge(repo, request("refs/heads/release/7", "main^{tree}")).then().statusCode(400);
  }

  @Test
  public void aBrowserSessionIsNotAMachine() throws Exception {
    String repo = seedThreeBranches();
    // The everyday suite runs as qits-auth-core's synthetic %test identity, which carries both
    // roles; a present forwarded header outranks it, which is how a caller holding only the
    // browser role is spelled (the measured idiom — see GitHostStorageClientTest).
    given()
        .header("X-Qits-User", "a-person")
        .header("X-Qits-Roles", "qits:admin")
        .contentType(ContentType.JSON)
        .body(request("refs/heads/release/8", "refs/heads/main"))
        .when()
        .post(API + repo + "/merges")
        .then()
        .statusCode(403);
  }

  // --- rebuild: the target's tip is no head ----------------------------------------------

  @Test
  public void aRebuildFoldsTheSourcesAloneWithTheirParentsInRequestOrder() throws Exception {
    String repo = seedThreeBranches();
    JsonPath first =
        merge(repo, rebuild(request("refs/heads/release/r1", "feature/a", "feature/b")))
            .then()
            .statusCode(200)
            .extract()
            .jsonPath();
    assertThat(first.getString("outcome"), is("merged"));
    assertThat(
        first.getList("parents", String.class),
        is(List.of(remoteSha(repo, "refs/heads/feature/a"), remoteSha(repo, "refs/heads/feature/b"))));

    // feature/b moves. The old fold is NOT a head: the new fold has the same two parents' shapes,
    // the new tip of b, and the old fold is not among them — no chain of merges.
    Path work = Files.createTempDirectory("qits-merge-rebuild");
    git(work, "clone", "-q", gitBase + "/" + repo, ".");
    git(work, "checkout", "-q", "-B", "feature/b", "origin/feature/b");
    write(work, "b-more.txt", "b\n");
    commit(work, "b again");
    push(work, repo, "feature/b");

    JsonPath second =
        merge(repo, rebuild(request("refs/heads/release/r1", "feature/a", "feature/b")))
            .then()
            .statusCode(200)
            .extract()
            .jsonPath();
    assertThat(second.getString("outcome"), is("merged"));
    assertThat(
        second.getList("parents", String.class),
        is(List.of(remoteSha(repo, "refs/heads/feature/a"), remoteSha(repo, "refs/heads/feature/b"))));
    assertThat(second.getList("parents", String.class), not(hasItem(first.getString("sha"))));
    assertThat(remoteSha(repo, "refs/heads/release/r1"), is(second.getString("sha")));
    // The old fold is gone from the branch's history: the branch was rebuilt, not extended.
    Path clone = clone(repo);
    assertThat(
        git(clone, "rev-list", "--count", "origin/main..origin/release/r1").trim(), is("4"));
  }

  @Test
  public void aRebuildOfTheSameHeadsInTheSameOrderIsUnchanged() throws Exception {
    String repo = seedThreeBranches();
    Map<String, Object> body = rebuild(request("refs/heads/release/r2", "feature/a", "feature/b"));
    String first = merge(repo, body).then().statusCode(200).extract().path("sha");

    JsonPath again = merge(repo, body).then().statusCode(200).extract().jsonPath();
    assertThat(again.getString("outcome"), is("unchanged"));
    assertThat(again.getString("sha"), is(first));

    // The order is part of the answer: reversed sources are a different fold, rebuilt.
    JsonPath reversed =
        merge(repo, rebuild(request("refs/heads/release/r2", "feature/b", "feature/a")))
            .then()
            .statusCode(200)
            .extract()
            .jsonPath();
    assertThat(reversed.getString("outcome"), is("merged"));
    assertThat(
        reversed.getList("parents", String.class),
        is(List.of(remoteSha(repo, "refs/heads/feature/b"), remoteSha(repo, "refs/heads/feature/a"))));
  }

  @Test
  public void aRebuildWithOneEffectiveHeadMovesTheTargetOntoItEvenBackwards() throws Exception {
    String repo = seedThreeBranches();
    // First an octopus of a and b ...
    merge(repo, rebuild(request("refs/heads/release/r3", "feature/a", "feature/b")))
        .then()
        .statusCode(200);
    // ... then b leaves the request: main is contained in a, so a is the one head left, and the
    // target lands on it although it is an ancestor of the old fold, not a descendant.
    JsonPath answer =
        merge(repo, rebuild(request("refs/heads/release/r3", "refs/heads/main", "feature/a")))
            .then()
            .statusCode(200)
            .extract()
            .jsonPath();
    assertThat(answer.getString("outcome"), is("fast-forward"));
    assertThat(answer.getList("skipped", String.class), contains("refs/heads/main"));
    assertThat(answer.getString("sha"), is(remoteSha(repo, "refs/heads/feature/a")));
    assertThat(remoteSha(repo, "refs/heads/release/r3"), is(remoteSha(repo, "refs/heads/feature/a")));

    JsonPath again =
        merge(repo, rebuild(request("refs/heads/release/r3", "refs/heads/main", "feature/a")))
            .then()
            .statusCode(200)
            .extract()
            .jsonPath();
    assertThat(again.getString("outcome"), is("unchanged"));
  }

  private static Map<String, Object> rebuild(Map<String, Object> body) {
    body.put("rebuild", true);
    return body;
  }

  // --- the plumbing -------------------------------------------------------------------------

  private io.restassured.response.Response merge(String repoId, Map<String, Object> body) {
    return given()
        .contentType(ContentType.JSON)
        .body(body)
        .when()
        .post(API + repoId + "/merges");
  }

  private io.restassured.response.Response containment(String repoId, String commit, String in) {
    return given()
        .queryParam("commit", commit)
        .queryParam("in", in)
        .when()
        .get(API + repoId + "/contains");
  }

  private static Map<String, Object> request(String target, String... sources) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("target", target);
    body.put("sources", List.of(sources));
    return body;
  }

  /** The same body with one directive on it, which is how a caller opts into a directed fold. */
  private static Map<String, Object> directed(Map<String, Object> body, String path, String gitlink) {
    body.put("resolutions", List.of(Map.of("path", path, "gitlink", gitlink)));
    return body;
  }

  // Pins of a submodule's repository, and deliberately commits nothing here holds: a gitlink names
  // an object of ANOTHER repository, and a fixture that used a local sha would hide a host that
  // tried to resolve one.
  private static final String PIN_BASE = "1111111111111111111111111111111111111111";
  private static final String PIN_LEFT = "2222222222222222222222222222222222222222";
  private static final String PIN_RIGHT = "3333333333333333333333333333333333333333";
  private static final String PIN_DIRECTED = "4444444444444444444444444444444444444444";

  /** {@code feature/left} and {@code feature/right}, pinning {@code sub} at different commits. */
  private String seedGitlinkConflict() throws Exception {
    String repoId = UUID.randomUUID().toString();
    Path work = seedGitlinkBase(repoId);
    git(work, "checkout", "-q", "-b", "feature/left", "main");
    pin(work, "sub", PIN_LEFT);
    commitIndex(work, "left");
    git(work, "checkout", "-q", "-b", "feature/right", "main");
    pin(work, "sub", PIN_RIGHT);
    commitIndex(work, "right");
    git(work, "checkout", "-q", "main");
    push(work, repoId, "main", "feature/left", "feature/right");
    return repoId;
  }

  /** The same two heads, arguing about a text file as well as about the pin. */
  private String seedGitlinkAndTextConflict() throws Exception {
    String repoId = UUID.randomUUID().toString();
    Path work = seedGitlinkBase(repoId);
    git(work, "checkout", "-q", "-b", "feature/left", "main");
    write(work, "shared.txt", "left\n");
    git(work, "add", "shared.txt");
    pin(work, "sub", PIN_LEFT);
    commitIndex(work, "left");
    git(work, "checkout", "-q", "-b", "feature/right", "main");
    write(work, "shared.txt", "right\n");
    git(work, "add", "shared.txt");
    pin(work, "sub", PIN_RIGHT);
    commitIndex(work, "right");
    git(work, "checkout", "-q", "main");
    push(work, repoId, "main", "feature/left", "feature/right");
    return repoId;
  }

  /**
   * Three heads where the FIRST fold is clean and the pin only breaks on the second — so the side
   * the report calls "ours" is a throwaway accumulator rather than any ref, which is the case a flat
   * report could not describe without leaking a sha that resolves nowhere.
   */
  private String seedLaterStepGitlinkConflict() throws Exception {
    String repoId = UUID.randomUUID().toString();
    Path work = seedGitlinkBase(repoId);
    git(work, "checkout", "-q", "-b", "feature/a", "main");
    write(work, "a.txt", "a\n");
    git(work, "add", "a.txt");
    commitIndex(work, "a");
    git(work, "checkout", "-q", "-b", "feature/left", "main");
    pin(work, "sub", PIN_LEFT);
    commitIndex(work, "left");
    git(work, "checkout", "-q", "-b", "feature/right", "main");
    pin(work, "sub", PIN_RIGHT);
    commitIndex(work, "right");
    git(work, "checkout", "-q", "main");
    push(work, repoId, "main", "feature/a", "feature/left", "feature/right");
    return repoId;
  }

  /** A fresh repository whose {@code main} carries a file and a submodule pinned at {@link #PIN_BASE}. */
  private Path seedGitlinkBase(String repoId) throws Exception {
    repositories.create(repoId, "main");
    Path work = Files.createTempDirectory("qits-merge-gitlink");
    git(work, "init", "-q", "-b", "main", ".");
    write(work, "base.txt", "base\n");
    git(work, "add", "base.txt");
    pin(work, "sub", PIN_BASE);
    commitIndex(work, "base");
    return work;
  }

  /** Two heads that rewrite the same line, for the report's "this is a plain file" half. */
  private String seedTextConflict() throws Exception {
    String repoId = UUID.randomUUID().toString();
    repositories.create(repoId, "main");
    Path work = Files.createTempDirectory("qits-merge-text");
    git(work, "init", "-q", "-b", "main", ".");
    write(work, "shared.txt", "base\n");
    commit(work, "base");
    git(work, "checkout", "-q", "-b", "feature/left", "main");
    write(work, "shared.txt", "left\n");
    commit(work, "left");
    git(work, "checkout", "-q", "-b", "feature/right", "main");
    write(work, "shared.txt", "right\n");
    commit(work, "right");
    git(work, "checkout", "-q", "main");
    push(work, repoId, "main", "feature/left", "feature/right");
    return repoId;
  }

  /**
   * A gitlink entry, written straight into the index: there is no submodule to clone here and no
   * need for one — a pin is a mode and an id, and {@code update-index} is how git itself writes one.
   */
  private static void pin(Path work, String path, String sha) throws Exception {
    git(work, "update-index", "--add", "--cacheinfo", "160000," + sha + "," + path);
  }

  /** Commit what the index says, without the {@code git add .} that would drop a phantom submodule. */
  private static void commitIndex(Path work, String message) throws Exception {
    git(work, "-c", "user.email=qits@local", "-c", "user.name=qits", "commit", "-q", "-m", message);
  }

  /** A worktree clone of the served repository, for reading back what the host actually wrote. */
  private Path clone(String repoId) throws Exception {
    Path work = Files.createTempDirectory("qits-merge-read");
    git(work, "clone", "-q", gitBase + "/" + repoId, ".");
    return work;
  }

  /** The parents git says a commit has — read from the repository, not from the answer under test. */
  private static List<String> parentsOf(Path clone, String sha) throws Exception {
    String[] line = git(clone, "rev-list", "--parents", "-n", "1", sha).trim().split("\\s+");
    return List.of(line).subList(1, line.length);
  }

  /** One tree entry, mode included — the only way to see a gitlink, which the browse route skips. */
  private static String treeEntry(Path clone, String rev, String path) throws Exception {
    return git(clone, "ls-tree", rev, "--", path).trim();
  }

  /** What the served repository says a ref is, over the wire, or a null-ish marker when it has none. */
  private String remoteSha(String repoId, String ref) throws Exception {
    String out = git(null, "ls-remote", gitBase + "/" + repoId, ref);
    for (String line : out.split("\n")) {
      String[] parts = line.trim().split("\\s+");
      if (parts.length == 2 && parts[1].equals(ref)) {
        return parts[0];
      }
    }
    return nullSha();
  }

  private static String nullSha() {
    return "";
  }

  private void push(Path work, String repoId, String... refs) throws Exception {
    String[] command = new String[3 + refs.length];
    command[0] = "push";
    command[1] = "-q";
    command[2] = gitBase + "/" + repoId;
    System.arraycopy(refs, 0, command, 3, refs.length);
    git(work, command);
  }

  private static void write(Path work, String name, String content) throws Exception {
    Files.createDirectories(work.resolve(name).getParent());
    Files.writeString(work.resolve(name), content);
  }

  private static void commit(Path work, String message) throws Exception {
    git(work, "add", ".");
    git(work, "-c", "user.email=qits@local", "-c", "user.name=qits", "commit", "-q", "-m", message);
  }

  private static String git(Path cwd, String... args) throws Exception {
    String[] command = new String[args.length + 1];
    command[0] = "git";
    System.arraycopy(args, 0, command, 1, args.length);
    ProcessBuilder pb = new ProcessBuilder(command);
    TestTokenMechanism.presentServiceClient(pb);
    if (cwd != null) {
      pb.directory(cwd.toFile());
    }
    pb.redirectErrorStream(true);
    Process p = pb.start();
    String out = new String(p.getInputStream().readAllBytes());
    if (p.waitFor() != 0) {
      throw new RuntimeException("git " + String.join(" ", args) + " failed:\n" + out);
    }
    return out;
  }
}
