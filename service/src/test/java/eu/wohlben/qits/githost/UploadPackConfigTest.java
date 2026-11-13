package eu.wohlben.qits.githost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.eclipse.jgit.internal.storage.dfs.DfsBlockCacheConfig;
import org.eclipse.jgit.internal.storage.dfs.DfsRepositoryDescription;
import org.eclipse.jgit.internal.storage.dfs.InMemoryRepository;
import org.eclipse.jgit.storage.pack.PackConfig;
import org.junit.jupiter.api.Test;

/** The two JGit tunings of qits-2207, as values: no delta search, and a block cache that fits. */
class UploadPackConfigTest {

  @Test
  void uploadPackSearchesForNoDeltasButReusesStoredOnes() throws Exception {
    try (InMemoryRepository repo =
        new InMemoryRepository(new DfsRepositoryDescription("upload-pack-config"))) {
      PackConfig config = GitHostRoutes.uploadPackConfig(repo);
      assertFalse(config.isDeltaCompress(), "the delta search is off");
      assertTrue(config.isReuseDeltas(), "stored deltas still go out as deltas");
      assertTrue(config.isReuseObjects(), "stored objects are still copied as they are");
    }
  }

  @Test
  void blockCacheHoldsAQuarterGibibyte() {
    DfsBlockCacheConfig config = JGitBlockCache.config();
    assertEquals(256L * 1024 * 1024, config.getBlockLimit());
    assertEquals(
        new DfsBlockCacheConfig().getBlockSize(), config.getBlockSize(), "JGit's block size");
  }
}
