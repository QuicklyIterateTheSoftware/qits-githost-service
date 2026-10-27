package eu.wohlben.qits.githost.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.eclipse.jgit.lib.CommitBuilder;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.TreeFormatter;
import org.eclipse.jgit.merge.MergeStrategy;
import org.eclipse.jgit.merge.ResolveMerger;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The repository's {@code info/attributes}, which is a value handed to the builder rather than a
 * file (qits-1691), seen the way the git host's fold sees it: {@code MergeStrategy.RECURSIVE}'s
 * in-core {@link ResolveMerger}, two heads over one base, nothing on a disk.
 *
 * <p>The case is the one that made the rule necessary. Every agent appends its own line to the end
 * of {@code memory/MEMORY.md}, so two branches that each did conflicted on every fold — both sides
 * inserted at the same spot. {@code merge=union} keeps both insertions, ours first.
 */
class InfoAttributesMergeTest {

  private static final PersonIdent AUTHOR = new PersonIdent("qits", "qits@local");
  private static final String RULE = "memory/MEMORY.md merge=union\n";
  private static final String BASE = "- [one](one.md) — the first memory\n";
  private static final String LEFT = "- [left](left.md) — appended on one branch\n";
  private static final String RIGHT = "- [right](right.md) — appended on the other\n";

  private InMemoryPackBlobStore blobs;
  private InMemoryPackCatalog catalog;

  @BeforeEach
  void setUp() {
    blobs = new InMemoryPackBlobStore();
    catalog = new InMemoryPackCatalog();
  }

  private QitsDfsRepository open(String repositoryId, String infoAttributes) {
    return new QitsDfsRepositoryBuilder()
        .setRepositoryId(repositoryId)
        .setPackBlobStore(blobs)
        .setPackCatalog(catalog)
        .setInfoAttributes(infoAttributes)
        .build();
  }

  @Test
  void withTheUnionRuleTwoAppendsToTheMemoryIndexMergeCleanlyAndKeepBothLines() throws Exception {
    try (QitsDfsRepository repository = open("union", RULE)) {
      repository.create("main");
      RevCommit[] heads = twoAppends(repository, "memory/MEMORY.md");

      try (ObjectInserter inserter = repository.newObjectInserter()) {
        ResolveMerger merger = fold(repository);
        // The fold's shape: one shared inserter, nothing flushed by the merge itself, one flush
        // once the whole octopus succeeded.
        merger.setObjectInserter(inserter);

        assertTrue(merger.merge(false, heads[0], heads[1]), "the union rule should fold cleanly");
        inserter.flush();
        assertEquals(
            BASE + LEFT + RIGHT,
            read(repository, merger.getResultTreeId(), "memory/MEMORY.md"),
            "both appended lines survive, ours first");
      }
    }
  }

  @Test
  void withoutTheRuleTheSameTwoAppendsConflict() throws Exception {
    // The before picture, kept as the proof the rule is what changed the answer: a repository built
    // without info attributes merges exactly as every DfsRepository did before qits-1691.
    try (QitsDfsRepository repository = open("plain", null)) {
      repository.create("main");
      RevCommit[] heads = twoAppends(repository, "memory/MEMORY.md");

      ResolveMerger merger = fold(repository);

      assertFalse(merger.merge(false, heads[0], heads[1]));
      assertTrue(merger.getUnmergedPaths().contains("memory/MEMORY.md"));
    }
  }

  @Test
  void theRuleIsScopedToItsPathAndAnotherFileStillConflicts() throws Exception {
    try (QitsDfsRepository repository = open("scoped", RULE)) {
      repository.create("main");
      RevCommit[] heads = twoAppends(repository, "README.md");

      ResolveMerger merger = fold(repository);

      assertFalse(merger.merge(false, heads[0], heads[1]));
      assertTrue(merger.getUnmergedPaths().contains("README.md"));
    }
  }

  @Test
  void blankTextMeansNoRules() throws Exception {
    // An empty configured value is how a deployment switches the rule off.
    try (QitsDfsRepository repository = open("blank", "  \n")) {
      assertNull(repository.createAttributesNodeProvider().getInfoAttributesNode());
      assertNull(repository.createAttributesNodeProvider().getGlobalAttributesNode());
    }
  }

  /** The merger exactly as {@code RepositoryRefsResource.fold} builds it: recursive, in core. */
  private static ResolveMerger fold(QitsDfsRepository repository) {
    ResolveMerger merger = (ResolveMerger) MergeStrategy.RECURSIVE.newMerger(repository, true);
    merger.setCommitNames(new String[] {"BASE", "refs/heads/release", "feature/right"});
    return merger;
  }

  /** A base holding {@code path}, and two children that each append one line of their own to it. */
  private static RevCommit[] twoAppends(QitsDfsRepository repository, String path)
      throws IOException {
    try (ObjectInserter inserter = repository.newObjectInserter();
        RevWalk walk = new RevWalk(repository)) {
      ObjectId base = commit(inserter, path, BASE, null);
      ObjectId left = commit(inserter, path, BASE + LEFT, base);
      ObjectId right = commit(inserter, path, BASE + RIGHT, base);
      inserter.flush();
      return new RevCommit[] {walk.parseCommit(left), walk.parseCommit(right)};
    }
  }

  /** One commit whose tree holds only {@code path} (at most one directory deep). */
  private static ObjectId commit(
      ObjectInserter inserter, String path, String content, ObjectId parent) throws IOException {
    ObjectId blob = inserter.insert(Constants.OBJ_BLOB, content.getBytes(StandardCharsets.UTF_8));
    int slash = path.indexOf('/');
    TreeFormatter root = new TreeFormatter();
    if (slash < 0) {
      root.append(path, FileMode.REGULAR_FILE, blob);
    } else {
      TreeFormatter dir = new TreeFormatter();
      dir.append(path.substring(slash + 1), FileMode.REGULAR_FILE, blob);
      root.append(path.substring(0, slash), FileMode.TREE, inserter.insert(dir));
    }
    CommitBuilder commit = new CommitBuilder();
    commit.setTreeId(inserter.insert(root));
    if (parent != null) {
      commit.setParentId(parent);
    }
    commit.setAuthor(AUTHOR);
    commit.setCommitter(AUTHOR);
    commit.setMessage(content);
    return inserter.insert(commit);
  }

  private static String read(QitsDfsRepository repository, ObjectId tree, String path)
      throws IOException {
    try (ObjectReader reader = repository.newObjectReader();
        TreeWalk walk = TreeWalk.forPath(reader, path, tree)) {
      return new String(reader.open(walk.getObjectId(0)).getBytes(), StandardCharsets.UTF_8);
    }
  }
}
