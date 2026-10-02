package eu.wohlben.qits.githost.loc;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.eclipse.jgit.internal.storage.dfs.DfsRepositoryDescription;
import org.eclipse.jgit.internal.storage.dfs.InMemoryRepository;
import org.eclipse.jgit.lib.CommitBuilder;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.TreeFormatter;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.junit.jupiter.api.Test;

/**
 * The scanner over a DFS (in-memory) repository, the same storage kind the host runs on: what it
 * counts, what it skips, and which column and category a line lands in.
 */
class RepositoryLocScannerTest {

  @Test
  void lockfilesAndPathsMarkedGeneratedOrVendoredAreNotCounted() throws Exception {
    List<LanguageLoc> counted =
        scan(
            Map.of(
                ".gitattributes", "src/app/api/** linguist-generated=true\n",
                "src/app/api/client.gen.ts", "a\nb\nc\n",
                "src/app/store.ts", "a\nb\n",
                "package-lock.json", "{\n}\n",
                "lib/.gitattributes", "vendor.js linguist-vendored\n",
                "lib/vendor.js", "a\n",
                "lib/own.js", "a\n"));
    assertEquals(
        List.of(
            new LanguageLoc("TypeScript", Language.Category.CODE, 2, 0),
            new LanguageLoc("JavaScript", Language.Category.CODE, 1, 0)),
        counted);
  }

  @Test
  void anUnsetOrFalseMarkStillCounts() throws Exception {
    List<LanguageLoc> counted =
        scan(
            Map.of(
                ".gitattributes", "*.ts linguist-generated\nkeep.ts -linguist-generated\nalso.ts linguist-generated=false\n",
                "gone.ts", "a\n",
                "keep.ts", "a\nb\n",
                "also.ts", "a\n"));
    assertEquals(List.of(new LanguageLoc("TypeScript", Language.Category.CODE, 3, 0)), counted);
  }

  @Test
  void goldenMastersAndPactsAreTestDataAndEachLanguageCarriesItsCategory() throws Exception {
    List<LanguageLoc> counted =
        scan(
            Map.of(
                "golden-masters/index.json", "{\n}\n",
                "pacts/a_b.json", "{\n}\n",
                "docs/openapi.yml", "a: 1\n",
                "README.md", "# x\n"));
    assertEquals(
        List.of(
            new LanguageLoc("JSON", Language.Category.DATA, 0, 4),
            new LanguageLoc("Markdown", Language.Category.DOCS, 1, 0),
            new LanguageLoc("YAML", Language.Category.DATA, 1, 0)),
        counted);
  }

  private static List<LanguageLoc> scan(Map<String, String> files) throws Exception {
    try (InMemoryRepository repo =
        new InMemoryRepository(new DfsRepositoryDescription("scanner-test"))) {
      ObjectId commitId;
      try (ObjectInserter inserter = repo.newObjectInserter()) {
        ObjectId tree = tree(inserter, new TreeMap<>(files), "");
        CommitBuilder commit = new CommitBuilder();
        PersonIdent who = new PersonIdent("t", "t@example.org", 0L, 0);
        commit.setTreeId(tree);
        commit.setAuthor(who);
        commit.setCommitter(who);
        commit.setMessage("x");
        commitId = inserter.insert(commit);
        inserter.flush();
      }
      try (RevWalk walk = new RevWalk(repo)) {
        RevCommit commit = walk.parseCommit(commitId);
        return RepositoryLocScanner.scan(repo, commit);
      }
    }
  }

  /** A tree for the files under {@code prefix}, subtrees first built recursively. */
  private static ObjectId tree(ObjectInserter inserter, TreeMap<String, String> files, String prefix)
      throws Exception {
    TreeMap<String, Object> entries = new TreeMap<>();
    for (Map.Entry<String, String> file : files.entrySet()) {
      if (!file.getKey().startsWith(prefix)) {
        continue;
      }
      String rest = file.getKey().substring(prefix.length());
      int slash = rest.indexOf('/');
      if (slash < 0) {
        entries.put(rest, file.getValue());
      } else {
        entries.putIfAbsent(rest.substring(0, slash), Boolean.TRUE);
      }
    }
    TreeFormatter formatter = new TreeFormatter();
    // Git orders a tree by name, with a directory compared as if it ended in '/'.
    TreeMap<String, Object> ordered = new TreeMap<>();
    for (Map.Entry<String, Object> e : entries.entrySet()) {
      ordered.put(e.getValue() instanceof Boolean ? e.getKey() + "/" : e.getKey(), e.getValue());
    }
    for (Map.Entry<String, Object> e : ordered.entrySet()) {
      if (e.getValue() instanceof Boolean) {
        String name = e.getKey().substring(0, e.getKey().length() - 1);
        formatter.append(name, FileMode.TREE, tree(inserter, files, prefix + name + "/"));
      } else {
        byte[] bytes = ((String) e.getValue()).getBytes(StandardCharsets.UTF_8);
        formatter.append(
            e.getKey(), FileMode.REGULAR_FILE, inserter.insert(Constants.OBJ_BLOB, bytes));
      }
    }
    return inserter.insert(formatter);
  }
}
