package eu.wohlben.qits.githost.contracts;

import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.githost.FakeRepositoryNameResolver;
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
  public static final String A_REPOSITORY_EXISTS = "a repository exists";
  public static final String TWO_REPOSITORIES = "two repositories";
  public static final String A_REPOSITORY_WITH_FILES_ON_MAIN = "a repository with files on main";
  public static final String A_REPOSITORY_MISSING_THE_REQUESTED_PATH =
      "a repository missing the requested path";
  public static final String A_REPOSITORY_MISSING_THE_REQUESTED_COMMIT =
      "a repository missing the requested commit";
  public static final String A_WRAPPER_REPOSITORY_WITH_A_SUBMODULE =
      "a wrapper repository with a submodule";
  public static final String A_REPOSITORY_WHOSE_MAIN_CONTAINS_A_COMMIT =
      "a repository whose main contains a commit";
  public static final String A_REPOSITORY_WITH_A_SIDE_BRANCH = "a repository with a side branch";
  public static final String A_REPOSITORY_WITHOUT_THE_GIVEN_BRANCH =
      "a repository without the given branch";
  public static final String A_REPOSITORY_WITH_A_BRANCH_TO_FOLD =
      "a repository with a branch to fold";
  public static final String A_REPOSITORY_WITH_A_BRANCH_THAT_CONFLICTS_WITH_MAIN =
      "a repository with a branch that conflicts with main";
  public static final String A_REPOSITORY_WITHOUT_THE_BRANCH_TO_FOLD =
      "a repository without the branch to fold";
  public static final String A_REPOSITORY_WITH_THE_RELEASE_TAG = "a repository with the release tag";
  public static final String A_REPOSITORY_DECLARING_ITS_DEPLOYMENTS =
      "a repository declaring its deployments";
  public static final String A_REPOSITORY_DECLARING_NO_DEPLOYMENTS =
      "a repository declaring no deployments";
  public static final String AN_UNNAMED_REPOSITORY_DECLARING_ITS_DEPLOYMENTS =
      "an unnamed repository declaring its deployments";
  public static final String A_REPOSITORY_DECLARING_ITS_CONFIGURATION =
      "a repository declaring its configuration";
  public static final String A_REPOSITORY_DECLARING_NO_CONFIGURATION =
      "a repository declaring no configuration";
  public static final String A_REPOSITORY_DECLARING_AN_EVENT_TRIGGER =
      "a repository declaring an event trigger";
  public static final String A_REPOSITORY_WITHOUT_A_CONFIG_DIRECTORY =
      "a repository without a config directory";

  /** The project every name-addressed state registers its repository under, with this name. */
  static final String REPO_NAME = "contract-service";

  /** The side branch a state creates: slashy, as automation and release branches are. */
  static final String SIDE_BRANCH = "maintenance/contract";

  /** The branch a fold state merges into main. */
  static final String FOLD_BRANCH = "feature/contract";

  /** The tag a release names, as {@code GoldenMasterRecordingTest}'s tag body sends it. */
  static final String RELEASE_TAG = "2026.101.120000";

  /** The deployer's two declarations, read by fixed path (qits-deployments' SpecSource). */
  static final String DEPLOYMENTS_PATH = ".config/qits/deployments.yml";

  static final String CONFIGURATION_PATH = ".config/qits/configuration.yml";

  /** The directory qits-ci lists for its event triggers, and the one trigger a state declares. */
  static final String CONFIG_DIRECTORY = ".config/qits";

  static final String EVENT_TRIGGER_PATH = ".config/qits/ci-event-upstream.yml";

  /** The trigger's body, a well-formed {@code ci-event-*.yml} (qits-ci's CiEventTriggerParser). */
  static final String EVENT_TRIGGER =
      "event: BuildSuccessful\n"
          + "when:\n"
          + "  - repoId: { exact: contract-upstream }\n"
          + "    branch: { exact: main }\n"
          + "steps:\n"
          + "  - image: qits/build-images/java-base:latest\n"
          + "    script: ./bump.sh\n";

  /** A well-formed commit sha no seeded repository holds. */
  static final String ABSENT_SHA = "0123456789abcdef0123456789abcdef01234567";

  /** The commit a wrapper's submodule gitlink pins: a sha of another repository, never resolved. */
  static final String SUBMODULE_SHA = "89abcdef0123456789abcdef0123456789abcdef";

  /**
   * The content states' tree: the pipeline config qits-ci reads, the manifests qits-maintenance
   * scans and qits-projects stamps. A file set of its own, so the lines-of-code recordings above do
   * not move.
   */
  static final Map<String, String> CONTENT_FILES =
      Map.of(
          ".config/qits/release.yml", "archetype: java-service\n",
          ".config/qits/deployments.yml", "services: {}\n",
          "pom.xml", "<project>\n  <version>1.0.0</version>\n</project>\n",
          "package.json", "{\n  \"name\": \"contract\",\n  \"version\": \"1.0.0\"\n}\n",
          "README.md", "# Contract repository\n");

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
  @Inject FakeRepositoryNameResolver names;

  private final Map<String, Supplier<Setup>> states = new LinkedHashMap<>();

  public ProviderStates() {
    states.put(A_REPOSITORY_WITH_COUNTED_LINES, this::aRepositoryWithCountedLines);
    states.put(A_REPOSITORY_NOT_COUNTED_YET, this::aRepositoryNotCountedYet);
    states.put(A_REPOSITORY_WITH_NO_COMMIT, this::aRepositoryWithNoCommit);
    states.put(NO_REPOSITORY_WITH_THE_GIVEN_ID, this::noRepositoryWithTheGivenId);
    states.put(TWO_REPOSITORIES_ONE_COUNTED, this::twoRepositoriesOneCounted);
    states.put(A_REPOSITORY_COUNTED_AT_AN_OLDER_COMMIT, this::aRepositoryCountedAtAnOlderCommit);
    states.put(A_REPOSITORY_EXISTS, this::aRepositoryExists);
    states.put(TWO_REPOSITORIES, this::twoRepositories);
    states.put(A_REPOSITORY_WITH_FILES_ON_MAIN, this::aRepositoryWithFilesOnMain);
    states.put(A_REPOSITORY_MISSING_THE_REQUESTED_PATH, this::aRepositoryMissingTheRequestedPath);
    states.put(
        A_REPOSITORY_MISSING_THE_REQUESTED_COMMIT, this::aRepositoryMissingTheRequestedCommit);
    states.put(A_WRAPPER_REPOSITORY_WITH_A_SUBMODULE, this::aWrapperRepositoryWithASubmodule);
    states.put(
        A_REPOSITORY_WHOSE_MAIN_CONTAINS_A_COMMIT, this::aRepositoryWhoseMainContainsACommit);
    states.put(A_REPOSITORY_WITH_A_SIDE_BRANCH, this::aRepositoryWithASideBranch);
    states.put(A_REPOSITORY_WITHOUT_THE_GIVEN_BRANCH, this::aRepositoryWithoutTheGivenBranch);
    states.put(A_REPOSITORY_WITH_A_BRANCH_TO_FOLD, this::aRepositoryWithABranchToFold);
    states.put(
        A_REPOSITORY_WITH_A_BRANCH_THAT_CONFLICTS_WITH_MAIN,
        this::aRepositoryWithABranchThatConflictsWithMain);
    states.put(A_REPOSITORY_WITHOUT_THE_BRANCH_TO_FOLD, this::aRepositoryWithoutTheBranchToFold);
    states.put(A_REPOSITORY_WITH_THE_RELEASE_TAG, this::aRepositoryWithTheReleaseTag);
    states.put(A_REPOSITORY_DECLARING_ITS_DEPLOYMENTS, this::aRepositoryDeclaringItsDeployments);
    states.put(A_REPOSITORY_DECLARING_NO_DEPLOYMENTS, this::aRepositoryDeclaringNoDeployments);
    states.put(
        AN_UNNAMED_REPOSITORY_DECLARING_ITS_DEPLOYMENTS,
        this::anUnnamedRepositoryDeclaringItsDeployments);
    states.put(
        A_REPOSITORY_DECLARING_ITS_CONFIGURATION, this::aRepositoryDeclaringItsConfiguration);
    states.put(A_REPOSITORY_DECLARING_NO_CONFIGURATION, this::aRepositoryDeclaringNoConfiguration);
    states.put(A_REPOSITORY_DECLARING_AN_EVENT_TRIGGER, this::aRepositoryDeclaringAnEventTrigger);
    states.put(A_REPOSITORY_WITHOUT_A_CONFIG_DIRECTORY, this::aRepositoryWithoutAConfigDirectory);
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

  private Setup aRepositoryExists() {
    String id = UUID.randomUUID().toString();
    create(id);
    return new Setup(params("repositoryId", id), List.of());
  }

  /** Two empty repositories, ids sorted, so the listing's order is the params' order. */
  private Setup twoRepositories() {
    List<String> ids =
        List.of(UUID.randomUUID().toString(), UUID.randomUUID().toString()).stream()
            .sorted()
            .toList();
    ids.forEach(this::create);
    return new Setup(
        params("firstRepositoryId", ids.get(0), "secondRepositoryId", ids.get(1)), List.of());
  }

  /**
   * {@link #CONTENT_FILES} on main, reachable by id and by name. {@code path} is a file, {@code
   * directory} the directory holding it, {@code sha} main's commit.
   */
  private Setup aRepositoryWithFilesOnMain() {
    Named repo = namedContentRepository();
    return new Setup(
        repo.params(
            "rev", "main",
            "sha", repo.commit().name(),
            "path", ".config/qits/release.yml",
            "directory", ".config/qits"),
        List.of());
  }

  /** The content repository, asked for a file and a directory it does not hold. */
  private Setup aRepositoryMissingTheRequestedPath() {
    Named repo = namedContentRepository();
    return new Setup(
        repo.params(
            "rev", "main", "path", ".config/qits/absent.yml", "directory", ".config/absent"),
        List.of());
  }

  /** The content repository, asked for a well-formed commit it does not hold. */
  private Setup aRepositoryMissingTheRequestedCommit() {
    Named repo = namedContentRepository();
    return new Setup(repo.params("sha", ABSENT_SHA), List.of());
  }

  /** A superproject: one submodule gitlink at {@code <directory>/<submodule>}, as the wrapper has. */
  private Setup aWrapperRepositoryWithASubmodule() {
    String id = UUID.randomUUID().toString();
    String projectId = UUID.randomUUID().toString();
    create(id);
    RevCommit commit =
        commit(
            id,
            null,
            Map.of(
                ".gitmodules",
                "[submodule \"qits-ci-service\"]\n"
                    + "\tpath = components/qits-ci/qits-ci-service\n"
                    + "\turl = ../qits-ci-service.git\n"),
            Map.of("components/qits-ci/qits-ci-service", SUBMODULE_SHA),
            "feat(contract-1): pin the submodule\n");
    move(id, "main", null, commit);
    names.register(projectId, REPO_NAME, id);
    return new Setup(
        params(
            "repositoryId", id,
            "projectId", projectId,
            "repoName", REPO_NAME,
            "rev", "main",
            "directory", "components/qits-ci",
            "submodule", "qits-ci-service"),
        List.of());
  }

  /** Main moved one commit past {@code commit}, so main contains it. {@code in} is main. */
  private Setup aRepositoryWhoseMainContainsACommit() {
    Named repo = namedContentRepository();
    RevCommit next =
        commit(
            repo.id(),
            repo.commit(),
            Map.of("README.md", "# Contract repository\n\nOne more line.\n"),
            Map.of(),
            "docs(contract-1): one more line\n");
    move(repo.id(), "main", repo.commit(), next);
    return new Setup(repo.params("commit", repo.commit().name(), "in", "main"), List.of());
  }

  /** {@link #SIDE_BRANCH} beside main, at main's commit. */
  private Setup aRepositoryWithASideBranch() {
    Named repo = namedContentRepository();
    move(repo.id(), SIDE_BRANCH, null, repo.commit());
    return new Setup(repo.params("branch", SIDE_BRANCH), List.of());
  }

  /** Main only: {@code branch} names a branch the repository does not have. */
  private Setup aRepositoryWithoutTheGivenBranch() {
    Named repo = namedContentRepository();
    return new Setup(repo.params("branch", SIDE_BRANCH), List.of());
  }

  /**
   * Main and {@link #FOLD_BRANCH} each bumped {@code pom.xml}'s version from 1.0.0, to 1.0.1 and to
   * 1.1.0, and the branch added a file: a fold with the version-pin rule merges, deciding 1.1.0.
   */
  private Setup aRepositoryWithABranchToFold() {
    Named repo = namedContentRepository();
    diverge(
        repo,
        Map.of("pom.xml", "<project>\n  <version>1.0.1</version>\n</project>\n"),
        Map.of(
            "pom.xml", "<project>\n  <version>1.1.0</version>\n</project>\n",
            "src/Feature.java", "class Feature {}\n"));
    return new Setup(repo.params("target", "refs/heads/main", "source", FOLD_BRANCH), List.of());
  }

  /** Main and {@link #FOLD_BRANCH} each rewrote README.md's one line: a conflict no rule decides. */
  private Setup aRepositoryWithABranchThatConflictsWithMain() {
    Named repo = namedContentRepository();
    diverge(
        repo,
        Map.of("README.md", "# The repository main describes\n"),
        Map.of("README.md", "# The repository the branch describes\n"));
    return new Setup(repo.params("target", "refs/heads/main", "source", FOLD_BRANCH), List.of());
  }

  /** Main only: {@code source} names a branch the repository does not have, so nothing folds. */
  private Setup aRepositoryWithoutTheBranchToFold() {
    Named repo = namedContentRepository();
    return new Setup(repo.params("target", "refs/heads/main", "source", FOLD_BRANCH), List.of());
  }

  /**
   * {@link #RELEASE_TAG} already on main's commit, a lightweight tag: tagging it again is the
   * {@code tag-exists} conflict, answered with the sha the ref holds.
   */
  private Setup aRepositoryWithTheReleaseTag() {
    Named repo = namedContentRepository();
    moveRef(repo.id(), Constants.R_TAGS + RELEASE_TAG, null, repo.commit());
    return new Setup(repo.params("sha", repo.commit().name()), List.of());
  }

  /** {@link #CONTENT_FILES}, which hold {@link #DEPLOYMENTS_PATH}; {@code path} names it. */
  private Setup aRepositoryDeclaringItsDeployments() {
    Named repo = namedContentRepository();
    return new Setup(repo.params("rev", "main", "path", DEPLOYMENTS_PATH), List.of());
  }

  /** {@link #CONTENT_FILES} without {@link #DEPLOYMENTS_PATH}; {@code path} names it all the same. */
  private Setup aRepositoryDeclaringNoDeployments() {
    Map<String, String> files = new TreeMap<>(CONTENT_FILES);
    files.remove(DEPLOYMENTS_PATH);
    Named repo = namedRepository(files, true);
    return new Setup(repo.params("rev", "main", "path", DEPLOYMENTS_PATH), List.of());
  }

  /**
   * The deployments declaration, held by a repository no name resolves to yet: the name-addressed
   * read misses, the id-addressed one finds it — the deployer's fallback.
   */
  private Setup anUnnamedRepositoryDeclaringItsDeployments() {
    Named repo = namedRepository(CONTENT_FILES, false);
    return new Setup(repo.params("rev", "main", "path", DEPLOYMENTS_PATH), List.of());
  }

  /** {@link #CONTENT_FILES} plus {@link #CONFIGURATION_PATH}; {@code path} names it. */
  private Setup aRepositoryDeclaringItsConfiguration() {
    Map<String, String> files = new TreeMap<>(CONTENT_FILES);
    files.put(CONFIGURATION_PATH, "keys: {}\n");
    Named repo = namedRepository(files, true);
    return new Setup(repo.params("rev", "main", "path", CONFIGURATION_PATH), List.of());
  }

  /** {@link #CONTENT_FILES}, which hold no {@link #CONFIGURATION_PATH}; {@code path} names it. */
  private Setup aRepositoryDeclaringNoConfiguration() {
    Named repo = namedContentRepository();
    return new Setup(repo.params("rev", "main", "path", CONFIGURATION_PATH), List.of());
  }

  /**
   * {@link #CONTENT_FILES} plus {@link #EVENT_TRIGGER_PATH}: qits-ci lists {@code directory} at
   * {@code rev} and reads {@code path} at the listing's {@code sha}. {@code body} is the trigger's
   * content, the bytes that blob answers.
   */
  private Setup aRepositoryDeclaringAnEventTrigger() {
    Map<String, String> files = new TreeMap<>(CONTENT_FILES);
    files.put(EVENT_TRIGGER_PATH, EVENT_TRIGGER);
    Named repo = namedRepository(files, true);
    return new Setup(
        repo.params(
            "rev", "main",
            "sha", repo.commit().name(),
            "directory", CONFIG_DIRECTORY,
            "path", EVENT_TRIGGER_PATH,
            "body", EVENT_TRIGGER),
        List.of());
  }

  /**
   * {@link #CONTENT_FILES} without anything under {@link #CONFIG_DIRECTORY}: listing it is a 404,
   * and the root tree at {@code rev} answers {@code sha} — the repository is there and declares
   * nothing (qits-ci's fallback).
   */
  private Setup aRepositoryWithoutAConfigDirectory() {
    Map<String, String> files = new TreeMap<>(CONTENT_FILES);
    files.keySet().removeIf(path -> path.startsWith(CONFIG_DIRECTORY + "/"));
    Named repo = namedRepository(files, true);
    return new Setup(
        repo.params("rev", "main", "sha", repo.commit().name(), "directory", CONFIG_DIRECTORY),
        List.of());
  }

  // --- the content repository ------------------------------------------------------------------

  /** A repository with {@link #CONTENT_FILES} on main, registered under a fresh project. */
  private record Named(String id, String projectId, RevCommit commit) {

    /** Its address — {@code repositoryId}, {@code projectId}, {@code repoName} — plus {@code more}. */
    Map<String, String> params(String... more) {
      String[] all = new String[more.length + 6];
      all[0] = "repositoryId";
      all[1] = id;
      all[2] = "projectId";
      all[3] = projectId;
      all[4] = "repoName";
      all[5] = REPO_NAME;
      System.arraycopy(more, 0, all, 6, more.length);
      return ProviderStates.params(all);
    }
  }

  private Named namedContentRepository() {
    return namedRepository(CONTENT_FILES, true);
  }

  /**
   * A repository with {@code files} on main under a fresh project; {@code registered} false leaves
   * the name unresolvable, so only its storage id reaches it.
   */
  private Named namedRepository(Map<String, String> files, boolean registered) {
    String id = UUID.randomUUID().toString();
    String projectId = UUID.randomUUID().toString();
    create(id);
    RevCommit commit =
        commit(id, null, files, Map.of(), "feat(contract-1): seed the contract repository\n");
    move(id, "main", null, commit);
    if (registered) {
      names.register(projectId, REPO_NAME, id);
    }
    return new Named(id, projectId, commit);
  }

  /** One commit on main and one on {@link #FOLD_BRANCH}, both on top of the seed. */
  private void diverge(Named repo, Map<String, String> onMain, Map<String, String> onBranch) {
    RevCommit main = commit(repo.id(), repo.commit(), onMain, Map.of(), "fix(contract-1): main\n");
    RevCommit branch =
        commit(repo.id(), repo.commit(), onBranch, Map.of(), "feat(contract-1): branch\n");
    move(repo.id(), "main", repo.commit(), main);
    move(repo.id(), FOLD_BRANCH, null, branch);
  }

  /**
   * Writes one commit, in-process: {@code parent}'s tree (or none) with {@code files} written and
   * {@code gitlinks} pinned. Fixed ident and time, so the sha is the same on every run.
   */
  private RevCommit commit(
      String id,
      RevCommit parent,
      Map<String, String> files,
      Map<String, String> gitlinks,
      String message) {
    try (Repository repo = repositories.open(id);
        ObjectInserter inserter = repo.newObjectInserter()) {
      DirCache index = DirCache.newInCore();
      DirCacheBuilder builder = index.builder();
      if (parent != null) {
        try (var reader = repo.newObjectReader();
            RevWalk walk = new RevWalk(reader)) {
          var tree = walk.parseCommit(parent).getTree();
          try (var walker = new org.eclipse.jgit.treewalk.TreeWalk(reader)) {
            walker.addTree(tree);
            walker.setRecursive(true);
            while (walker.next()) {
              String path = walker.getPathString();
              if (files.containsKey(path) || gitlinks.containsKey(path)) {
                continue;
              }
              DirCacheEntry kept = new DirCacheEntry(path);
              kept.setFileMode(walker.getFileMode(0));
              kept.setObjectId(walker.getObjectId(0));
              builder.add(kept);
            }
          }
        }
      }
      for (Map.Entry<String, String> file : files.entrySet()) {
        DirCacheEntry entry = new DirCacheEntry(file.getKey());
        entry.setFileMode(FileMode.REGULAR_FILE);
        entry.setObjectId(
            inserter.insert(Constants.OBJ_BLOB, file.getValue().getBytes(StandardCharsets.UTF_8)));
        builder.add(entry);
      }
      for (Map.Entry<String, String> gitlink : gitlinks.entrySet()) {
        DirCacheEntry entry = new DirCacheEntry(gitlink.getKey());
        entry.setFileMode(FileMode.GITLINK);
        entry.setObjectId(ObjectId.fromString(gitlink.getValue()));
        builder.add(entry);
      }
      builder.finish(); // sorts the entries
      CommitBuilder commit = new CommitBuilder();
      commit.setTreeId(index.writeTree(inserter));
      if (parent != null) {
        commit.setParentId(parent);
      }
      commit.setAuthor(IDENT);
      commit.setCommitter(IDENT);
      commit.setMessage(message);
      ObjectId commitId = inserter.insert(commit);
      inserter.flush();
      try (RevWalk walk = new RevWalk(repo)) {
        return walk.parseCommit(commitId);
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** Moves (or creates, {@code from} null) a branch, in-process: no push, so nothing announced. */
  private void move(String id, String branch, RevCommit from, RevCommit to) {
    moveRef(id, Constants.R_HEADS + branch, from, to);
  }

  /** {@link #move} for any ref, named in full. */
  private void moveRef(String id, String ref, RevCommit from, RevCommit to) {
    try (Repository repo = repositories.open(id)) {
      RefUpdate update = repo.updateRef(ref);
      update.setNewObjectId(to);
      update.setExpectedOldObjectId(from == null ? ObjectId.zeroId() : from);
      RefUpdate.Result result = update.update();
      if (result != RefUpdate.Result.NEW && result != RefUpdate.Result.FAST_FORWARD) {
        throw new IllegalStateException("Moving " + ref + " of " + id + ": " + result);
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
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
