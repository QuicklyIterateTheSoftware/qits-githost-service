package eu.wohlben.qits.githost;

import eu.wohlben.qits.githost.persistence.RepositoryNameStore;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Optional;
import org.jboss.logging.Logger;

/**
 * The shipped {@link RepositoryNameResolver}: it answers from this host's own name index ({@link
 * RepositoryNameStore}), which qits-projects fills through {@code PUT /git/:repoId/names}.
 *
 * <p><b>The index first, and the public clone url no longer depends on qits-projects being up.</b> A
 * name the index holds is served from this service's own database, with no call out — the lookup
 * every clone, fetch and push of {@code /git/:projectId/:repoName} makes. The first segment may be
 * the project's id or its slug ({@link RepositoryNameStore#resolve}).
 *
 * <p><b>A database that cannot be asked is an outage, not a miss.</b> The store throws rather than
 * answering empty, and that is turned into {@link RepositoryNameResolver.Unavailable} here — a 503
 * at the routes — and NOT into a fallback lookup: a fault is not evidence the name is absent, and
 * dialling qits-projects for every request while the database is gone would only add a second
 * dependency to a request that already cannot be answered honestly.
 *
 * <p><b>On a MISS only, it asks {@link HttpRepositoryNameResolver} — the transitional fallback.</b>
 * Until qits-projects has stated every repository's names here, a name the index does not hold may
 * still be one qits-projects knows, so the old lookup answers it (cached, and with its own
 * miss/outage line: its 404 is this resolver's empty, its outage this resolver's {@code
 * Unavailable}). <b>qits-1282 removes the fallback</b> once qits-projects has backfilled every name;
 * from then on a miss in the index is the answer.
 */
@ApplicationScoped
public class StoredRepositoryNameResolver implements RepositoryNameResolver {

  private static final Logger LOG = Logger.getLogger(StoredRepositoryNameResolver.class);

  @Inject RepositoryNameStore store;

  /** The HTTP lookup a miss falls back to. Removed by qits-1282. */
  @Inject HttpRepositoryNameResolver fallback;

  @Override
  public Optional<String> resolveRepositoryId(String projectId, String name) {
    if (projectId == null || name == null) {
      return Optional.empty();
    }
    Optional<String> held;
    try {
      held = store.resolve(projectId, name);
    } catch (RuntimeException e) {
      LOG.warnf("name index read %s/%s failed: %s", projectId, name, e.toString());
      throw new RepositoryNameResolver.Unavailable(
          "the name index could not be read for " + projectId + "/" + name, e);
    }
    if (held.isPresent()) {
      return held;
    }
    return fallback.resolveRepositoryId(projectId, name);
  }
}
