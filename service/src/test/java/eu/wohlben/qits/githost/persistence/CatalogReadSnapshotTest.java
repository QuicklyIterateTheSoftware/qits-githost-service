package eu.wohlben.qits.githost.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import eu.wohlben.qits.githost.storage.PackDescription;
import eu.wohlben.qits.githost.storage.PackFile;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import org.eclipse.jgit.lib.CommitBuilder;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.TreeFormatter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * <b>A catalog read sees one snapshot: every pack it lists comes with its files, and a pack commit
 * landing while it runs is seen whole or not at all</b> (qits-1823).
 *
 * <p>The incident: {@code refs/heads/main} of a live repository read back as a commit two days old,
 * one second after a push. The read was two SELECTs at READ COMMITTED, where each statement takes
 * its own snapshot, and a reftable compaction — one commit that adds the compacted table and removes
 * the tables it replaced — landed between them. The reader got the replaced tables from the first
 * SELECT and none of their files from the second, JGit dropped those file-less tables without a
 * word, and refs resolved from whatever lay beneath them.
 *
 * <p><b>How the commit is put in the middle.</b> {@link CatalogReadInterleave} runs it, on another
 * thread and therefore another transaction, at the moment the reading thread prepares its first
 * {@code git_pack_file} statement. Against the old two-statement read that is exactly the incident's
 * window — packs already read, files not yet — and both tests below fail there: the first finds
 * packs listed without files, the second finds the repository's refs gone. Against the fixed
 * one-statement read the same position is simply before the read, so the read sees the commit
 * whole. There is no sleep and no timing: the interleaving is forced, so the result is the same on
 * every run.
 *
 * <p>Each test also asserts that the interleaved work <i>did</i> run and did remove packs, because a
 * read that merely missed the window would prove nothing.
 */
@QuarkusTest
public class CatalogReadSnapshotTest {

  @Inject CatalogRepository catalog;

  @Inject DfsGitRepositoryProvider repositories;

  private final ExecutorService writer = Executors.newSingleThreadExecutor();

  @AfterEach
  void tearDown() {
    CatalogReadInterleave.disarm();
    writer.shutdownNow();
  }

  /**
   * The catalog-level invariant, on hand-made descriptions: two packs replaced by a third in one
   * commit, and a read racing that commit returns either both old packs with their files or the new
   * one with its file — never the old packs stripped of theirs, which is what the two-statement read
   * returned.
   */
  @Test
  public void aReadRacingARepackSeesTheWholeOfOneSideOfIt() {
    String repoId = UUID.randomUUID().toString();
    PackDescription a = pack("pack-a");
    PackDescription b = pack("pack-b");
    PackDescription c = pack("pack-c");
    catalog.commit(repoId, List.of(a, b), List.of());

    CatalogReadInterleave.arm(onTheWriter(() -> catalog.commit(repoId, List.of(c), List.of(a, b))));
    List<PackDescription> read = catalog.list(repoId);

    assertFalse(CatalogReadInterleave.pending(), "the interleaved repack never ran");
    for (PackDescription pack : read) {
      assertFalse(
          pack.files().isEmpty(),
          "pack " + pack.packName() + " was listed without its files — a read of two snapshots");
    }
    Set<String> names = names(read);
    assertTrue(
        names.equals(Set.of("pack-a", "pack-b")) || names.equals(Set.of("pack-c")),
        "the read mixed the catalog before and after the repack: " + names);
  }

  /** A pack with no file rows still reads back, as a pack with no files — the left in the join. */
  @Test
  public void aPackWithNoFilesStillReadsBack() {
    String repoId = UUID.randomUUID().toString();
    PackDescription bare =
        new PackDescription("pack-bare", "INSERT", 1L, 0L, 0L, 1L, 1L, 2, List.of());
    catalog.commit(repoId, List.of(bare, pack("pack-full")), List.of());

    Map<String, PackDescription> read =
        catalog.list(repoId).stream()
            .collect(Collectors.toMap(PackDescription::packName, pack -> pack));

    assertEquals(Set.of("pack-bare", "pack-full"), read.keySet());
    assertTrue(read.get("pack-bare").files().isEmpty());
    assertEquals(1, read.get("pack-full").files().size());
  }

  /**
   * The incident end to end, through JGit: a ref update on one repository instance — which writes a
   * reftable and compacts the top of the stack, removing the tables it replaced — lands in the middle
   * of another instance opening the repository and resolving {@code refs/heads/main}.
   *
   * <p>The interleaved update lands before the fixed read's one statement, so the reader must see
   * the newest value. On the old read it found the replaced tables without files and resolved
   * nothing above them: the repository read as absent, or {@code main} as an older commit.
   */
  @Test
  public void aRefReadRacingACompactionResolvesTheNewestValue() throws Exception {
    String repoId = UUID.randomUUID().toString();
    repositories.create(repoId, "main");
    ObjectId first;
    try (Repository repo = repositories.open(repoId)) {
      first = commit(repo, null, "first");
      update(repo, null, first);
    }
    Set<String> before = names(catalog.list(repoId));

    AtomicReference<ObjectId> newest = new AtomicReference<>();
    CatalogReadInterleave.arm(
        onTheWriter(
            () -> {
              try (Repository repo = repositories.open(repoId)) {
                ObjectId second = commit(repo, first, "second");
                update(repo, first, second);
                newest.set(second);
              } catch (IOException e) {
                throw new IllegalStateException(e);
              }
            }));

    ObjectId resolved;
    try (Repository repo = repositories.open(repoId)) {
      assertNotNull(repo, "the repository read as absent while a ref update compacted its stack");
      Ref main = repo.exactRef(Constants.R_HEADS + "main");
      assertNotNull(main, "refs/heads/main vanished while a ref update compacted the stack");
      resolved = main.getObjectId();
    }

    assertFalse(CatalogReadInterleave.pending(), "the interleaved ref update never ran");
    Set<String> removed = new HashSet<>(before);
    removed.removeAll(names(catalog.list(repoId)));
    assertFalse(removed.isEmpty(), "the ref update compacted nothing, so nothing raced");
    assertEquals(
        newest.get(), resolved, "refs/heads/main resolved from a stale mix of the reftable stack");
  }

  /**
   * Wraps work so that, when the seam fires, it runs to completion on the writer thread — its own
   * request context and its own transaction, committed — while the reader waits mid-read.
   */
  private Runnable onTheWriter(Runnable work) {
    return () -> {
      try {
        writer.submit(work).get(60, TimeUnit.SECONDS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        fail("interrupted while the interleaved write ran");
      } catch (ExecutionException | TimeoutException e) {
        throw new IllegalStateException("the interleaved write failed", e);
      }
    };
  }

  private static ObjectId commit(Repository repo, ObjectId parent, String message)
      throws IOException {
    try (ObjectInserter inserter = repo.newObjectInserter()) {
      CommitBuilder commit = new CommitBuilder();
      commit.setTreeId(inserter.insert(new TreeFormatter()));
      if (parent != null) {
        commit.setParentId(parent);
      }
      PersonIdent who = new PersonIdent("qits-1823", "qits-1823@example.invalid");
      commit.setAuthor(who);
      commit.setCommitter(who);
      commit.setMessage(message);
      ObjectId id = inserter.insert(commit);
      inserter.flush();
      return id;
    }
  }

  private static void update(Repository repo, ObjectId expected, ObjectId next)
      throws IOException {
    RefUpdate update = repo.updateRef(Constants.R_HEADS + "main");
    update.setExpectedOldObjectId(expected == null ? ObjectId.zeroId() : expected);
    update.setNewObjectId(next);
    RefUpdate.Result result = update.update();
    assertTrue(
        result == RefUpdate.Result.NEW || result == RefUpdate.Result.FAST_FORWARD,
        "refs/heads/main update was " + result);
  }

  private static Set<String> names(List<PackDescription> packs) {
    return packs.stream().map(PackDescription::packName).collect(Collectors.toSet());
  }

  /** One pack with one file — the smallest thing the catalog will store and read back. */
  private static PackDescription pack(String packName) {
    return new PackDescription(
        packName,
        "INSERT",
        1L,
        1L,
        0L,
        1L,
        1L,
        2,
        List.of(new PackFile("pack", "blob-" + packName, 42L, 0)));
  }
}
