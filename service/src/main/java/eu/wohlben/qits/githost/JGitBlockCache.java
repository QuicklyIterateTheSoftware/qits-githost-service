package eu.wohlben.qits.githost;

import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import org.eclipse.jgit.internal.storage.dfs.DfsBlockCache;
import org.eclipse.jgit.internal.storage.dfs.DfsBlockCacheConfig;

/**
 * Sizes JGit's {@link DfsBlockCache} — the cache of pack blocks every repository read goes through
 * — once, at startup. {@code StartupEvent} fires before the HTTP server opens, so no git request
 * ever reads through the default.
 *
 * <p>The cache is a JVM-wide static, not a per-repository setting: one {@code reconfigure} sizes it
 * for every repository this process serves. Left unconfigured it was JGit's 32 MB default, which
 * thrashed on qits-landing-app's 188 MB of packs — one clone read ~1 GB of blocks out of the
 * postgres chunk rows, and a warm clone was no faster than a cold one (qits-2207). 256 MiB holds
 * that repository whole. The block size stays JGit's default.
 *
 * <p>Nothing here for native-image: {@code DfsBlockCache} is already {@code
 * --initialize-at-run-time} (application.properties), so this call is what builds it, and {@link
 * DfsBlockCacheConfig} is plain configuration read by no reflection.
 */
@ApplicationScoped
class JGitBlockCache {

  /** Holds landing-app's 188 MB of packs; see the class comment. */
  static final long BLOCK_LIMIT = 256L * 1024 * 1024;

  static DfsBlockCacheConfig config() {
    return new DfsBlockCacheConfig().setBlockLimit(BLOCK_LIMIT);
  }

  void configure(@Observes StartupEvent startup) {
    DfsBlockCache.reconfigure(config());
  }
}
