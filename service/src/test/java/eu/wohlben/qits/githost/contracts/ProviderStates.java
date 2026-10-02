package eu.wohlben.qits.githost.contracts;

import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.githost.GitRepositoryProvider;
import eu.wohlben.qits.githost.loc.LocResponse;
import eu.wohlben.qits.githost.loc.RepositoryLocScanner;
import eu.wohlben.qits.githost.persistence.RepositoryLocStore;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Supplier;
import org.eclipse.jgit.dircache.DirCache;
import org.eclipse.jgit.dircache.DirCacheBuilder;
import org.eclipse.jgit.dircache.DirCacheEntry;
import org.eclipse.jgit.lib.CommitBuilder;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;

/**
 * <b>The provider states qits-githost answers for</b>: a state name → a setup that seeds
 * repositories with fresh ids and returns the state's parameters. The same shape as
 * qits-projects-service's, the first provider.
 *
 * <p>Two callers. {@link GoldenMasterRecordingTest} runs a state before recording each operation
 * that names it, and {@code ConsumerPactVerificationTest}'s {@code @State} methods delegate here.
 *
 * <p><b>Every state is parallel-safe and assumes nothing about the database.</b> Repository ids are
 * fresh UUIDs; lists are filtered down to the state's own repositories by the recorder.
 *
 * <p><b>The commits are deterministic.</b> Content, author, committer and time are fixed, so the
 * seeded commit has the same sha on every run and the golden masters need not freeze it. The
 * commit is written in-process through JGit, not pushed: a push would queue a scan ({@code
 * LocAnnouncer}), and the "not counted yet" state must stay uncounted.
 */
@ApplicationScoped
public class ProviderStates {

  public static final String A_REPOSITORY_WITH_COUNTED_LINES = "a repository with counted lines";
  public static final String A_REPOSITORY_NOT_COUNTED_YET = "a repository not counted yet";
  public static final String A_REPOSITORY_WITH_NO_COMMIT = "a repository with no commit";
  public static final String NO_REPOSITORY_WITH_THE_GIVEN_ID = "no repository with the given id";
  public static final String TWO_REPOSITORIES_ONE_COUNTED = "two repositories, one counted";
  public static final String A_REPOSITORY_COUNTED_AT_AN_OLDER_COMMIT =
      "a repository counted at an older commit";

  /**
   * The seeded tree: Java and TypeScript, each with main and test code, plus one file of each
   * other category (JSON data, Markdown docs) and two files that must NOT be counted: a lockfile
   * and a generated client marked in {@code .gitattributes}.
   */
  static final Map<String, String> FILES =
      Map.of(
          "src/main/java/App.java", "class App {\n  void run() {}\n}\n",
          "src/test/java/AppTest.java", "class AppTest {\n}\n",
          "web/app.ts", "export const a = 1;\nexport const b = 2;\nexport const c = 3;\nexport {};\n",
          "web/app.spec.ts", "it('runs', () => {});\n",
          "README.md", "# Contract repository\n",
          "package.json", "{\n  \"name\": \"contract\"\n}\n",
          "package-lock.json", "{\n  \"lockfileVersion\": 3\n}\n",
          ".gitattributes", "web/api/** linguist-generated=true\n",
          "web/api/client.gen.ts", "export const generated = 1;\n");

  private static final PersonIdent IDENT =
      new PersonIdent(
          "qits contract", "contract@qits.test", Instant.parse("2026-01-01T00:00:00Z"),
          ZoneOffset.UTC);

  /** What a state hands back: its parameters, keys sorted, and no unique tokens (none needed). */
  public record Setup(Map<String, String> params, List<String> uniqueTokens) {}

  @Inject GitRepositoryProvider repositories;
  @Inject RepositoryLocStore locStore;
  @Inject ObjectMapper mapper;

  private final Map<String, Supplier<Setup>> states = new LinkedHashMap<>();

  public ProviderStates() {
    states.put(A_REPOSITORY_WITH_COUNTED_LINES, this::aRepositoryWithCountedLines);
    states.put(A_REPOSITORY_NOT_COUNTED_YET, this::aRepositoryNotCountedYet);
    states.put(A_REPOSITORY_WITH_NO_COMMIT, this::aRepositoryWithNoCommit);
    states.put(NO_REPOSITORY_WITH_THE_GIVEN_ID, this::noRepositoryWithTheGivenId);
    states.put(TWO_REPOSITORIES_ONE_COUNTED, this::twoRepositoriesOneCounted);
    states.put(A_REPOSITORY_COUNTED_AT_AN_OLDER_COMMIT, this::aRepositoryCountedAtAnOlderCommit);
  }

  /** Every state name this provider answers for. */
  public Set<String> names() {
    return Collections.unmodifiableSet(states.keySet());
  }

  /** Runs the named state; an unknown name is a programming error, not an empty state. */
  public Setup setUp(String state) {
    Supplier<Setup> setup = states.get(state);
    if (setup == null) {
      throw new IllegalArgumentException(
          "No provider state '" + state + "' — this provider answers for " + states.keySet());
    }
    return setup.get();
  }

  /** {@link #setUp} for a pact {@code @State} method, which returns only the params. */
  public Map<String, String> params(String state) {
    return setUp(state).params();
  }

  /** The state's slug: lower-cased, every run of non-alphanumerics replaced by {@code -}. */
  public static String slug(String state) {
    return state.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-");
  }

  // --- the states ------------------------------------------------------------------------------

  private Setup aRepositoryWithCountedLines() {
    String id = UUID.randomUUID().toString();
    RevCommit commit = seed(id);
    count(id, commit);
    return new Setup(params("repositoryId", id), List.of());
  }

  private Setup aRepositoryNotCountedYet() {
    String id = UUID.randomUUID().toString();
    seed(id);
    return new Setup(params("repositoryId", id), List.of());
  }

  private Setup aRepositoryWithNoCommit() {
    String id = UUID.randomUUID().toString();
    create(id);
    return new Setup(params("repositoryId", id), List.of());
  }

  /**
   * A list that mixes both: one repository counted, one not counted yet. The counted one gets the
   * smaller of two fresh ids, so it always lists first (the endpoint sorts by id) and the golden
   * master does not depend on which random id came out lower.
   */
  private Setup twoRepositoriesOneCounted() {
    List<String> ids =
        List.of(UUID.randomUUID().toString(), UUID.randomUUID().toString()).stream()
            .sorted()
            .toList();
    String counted = ids.get(0);
    String pending = ids.get(1);
    count(counted, seed(counted));
    seed(pending);
    return new Setup(
        params("countedRepositoryId", counted, "pendingRepositoryId", pending), List.of());
  }

  /**
   * Counted, then {@code main} moved on by one commit nobody counted yet: the list answers the
   * older count as STALE. The second commit adds a TypeScript file, so its count would differ.
   */
  private Setup aRepositoryCountedAtAnOlderCommit() {
    String id = UUID.randomUUID().toString();
    RevCommit first = seed(id);
    count(id, first);
    advance(id, first);
    return new Setup(params("repositoryId", id), List.of());
  }

  private Setup noRepositoryWithTheGivenId() {
    return new Setup(params("repositoryId", UUID.randomUUID().toString()), List.of());
  }

  // --- seeding ---------------------------------------------------------------------------------

  private void create(String id) {
    try {
      repositories.create(id, "main");
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** Creates the repository and commits {@link #FILES} onto {@code main}, in-process. */
  RevCommit seed(String id) {
    create(id);
    try (Repository repo = repositories.open(id);
        ObjectInserter inserter = repo.newObjectInserter()) {
      DirCache index = DirCache.newInCore();
      DirCacheBuilder builder = index.builder();
      for (Map.Entry<String, String> file : new TreeMap<>(FILES).entrySet()) {
        DirCacheEntry entry = new DirCacheEntry(file.getKey());
        entry.setFileMode(FileMode.REGULAR_FILE);
        entry.setObjectId(
            inserter.insert(Constants.OBJ_BLOB, file.getValue().getBytes(StandardCharsets.UTF_8)));
        builder.add(entry);
      }
      builder.finish();
      ObjectId tree = index.writeTree(inserter);
      CommitBuilder commit = new CommitBuilder();
      commit.setTreeId(tree);
      commit.setAuthor(IDENT);
      commit.setCommitter(IDENT);
      commit.setMessage("feat(contract-1): seed the contract repository\n");
      ObjectId commitId = inserter.insert(commit);
      inserter.flush();
      RefUpdate update = repo.updateRef(Constants.R_HEADS + "main");
      update.setNewObjectId(commitId);
      update.setExpectedOldObjectId(ObjectId.zeroId());
      RefUpdate.Result result = update.update();
      if (result != RefUpdate.Result.NEW) {
        throw new IllegalStateException("Seeding " + id + " moved main: " + result);
      }
      try (RevWalk walk = new RevWalk(repo)) {
        return walk.parseCommit(commitId);
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** Commits one more file on top of {@code parent} and moves {@code main} to it, in-process. */
  private void advance(String id, RevCommit parent) {
    try (Repository repo = repositories.open(id);
        ObjectInserter inserter = repo.newObjectInserter()) {
      DirCache index = DirCache.newInCore();
      DirCacheBuilder builder = index.builder();
      builder.addTree(new byte[0], 0, repo.newObjectReader(), parent.getTree());
      DirCacheEntry added = new DirCacheEntry("web/more.ts");
      added.setFileMode(FileMode.REGULAR_FILE);
      added.setObjectId(
          inserter.insert(
              Constants.OBJ_BLOB, "export const d = 4;\n".getBytes(StandardCharsets.UTF_8)));
      builder.add(added);
      builder.finish();
      CommitBuilder commit = new CommitBuilder();
      commit.setTreeId(index.writeTree(inserter));
      commit.setParentId(parent);
      commit.setAuthor(IDENT);
      commit.setCommitter(IDENT);
      commit.setMessage("feat(contract-1): one more line nobody counted yet\n");
      ObjectId commitId = inserter.insert(commit);
      inserter.flush();
      RefUpdate update = repo.updateRef(Constants.R_HEADS + "main");
      update.setNewObjectId(commitId);
      update.setExpectedOldObjectId(parent);
      RefUpdate.Result result = update.update();
      if (result != RefUpdate.Result.FAST_FORWARD) {
        throw new IllegalStateException("Advancing " + id + " moved main: " + result);
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** Scans the commit and stores its summary, as the push path's indexer would. */
  private void count(String id, RevCommit commit) {
    try (Repository repo = repositories.open(id)) {
      var languages = RepositoryLocScanner.scan(repo, commit);
      locStore.saveQuietly(
          id, commit.name(), mapper.writeValueAsString(new LocResponse(commit.name(), languages)));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static Map<String, String> params(String... keysAndValues) {
    Map<String, String> out = new TreeMap<>();
    for (int i = 0; i < keysAndValues.length; i += 2) {
      out.put(keysAndValues[i], keysAndValues[i + 1]);
    }
    return Collections.unmodifiableMap(out);
  }
}
