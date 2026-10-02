package eu.wohlben.qits.githost.persistence;

import eu.wohlben.qits.db.DbRetry;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.control.ActivateRequestContext;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * The lines-of-code memo: reads and writes {@link GitRepositoryLoc} rows.
 *
 * <p><b>Fail-soft in both directions, unlike every other store here.</b> The memo is an
 * optimization over a scanner that is always available and always right — a read that cannot answer
 * falls back to a fresh scan, a write that cannot land is a scan repeated on the next ask. So
 * neither half retries ({@code DbRetry} would hold a caller for an answer the scanner already has)
 * and neither throws: a warning is the whole failure mode. Contrast {@code
 * RepositoryProtectionStore}, whose write has no such fallback and therefore waits.
 *
 * <p>Called from the browse resource's request thread and from the LOC indexer's own worker thread;
 * the latter has no request context, hence {@code @ActivateRequestContext} — the same shape every
 * store in this package documents.
 */
@ApplicationScoped
public class RepositoryLocStore {

  private static final Logger LOG = Logger.getLogger(RepositoryLocStore.class);

  /**
   * How many summaries one repository keeps. Enough for every branch tip anyone browses plus a few
   * historical shas; past it the oldest by {@code computedAt} go, and losing one costs a rescan.
   */
  static final int KEPT_PER_REPOSITORY = 20;

  @ConfigProperty(name = "qits.githost.db-retry-deadline", defaultValue = "15S")
  Duration dbRetryDeadline;

  /** The stored summary JSON for one commit, or empty — for absence and for a store that is down. */
  @ActivateRequestContext
  public Optional<String> find(String repositoryId, String commitSha) {
    try {
      return QuarkusTransaction.requiringNew()
          .call(
              () -> {
                GitRepositoryLoc row =
                    GitRepositoryLoc.findById(new GitRepositoryLocId(repositoryId, commitSha));
                return row == null ? Optional.<String>empty() : Optional.of(row.payload);
              });
    } catch (RuntimeException e) {
      LOG.warnf(e, "could not read the loc summary of %s@%s; scanning instead", repositoryId,
          commitSha);
      return Optional.empty();
    }
  }

  /**
   * The stored summary of each {@code (repository, commit)} pair that has one, keyed by the pair.
   * A pair with no row is absent from the map.
   *
   * <p><b>Strict, unlike the other reads here.</b> The bulk list ({@code LocListResource}) has no
   * scanner to fall back on inside its request, so a store that cannot answer must throw — reading
   * it as "nothing stored" would mark every repository pending. A short outage is waited out
   * ({@link DbRetry}).
   */
  @ActivateRequestContext
  public Map<GitRepositoryLocId, String> findAll(Collection<GitRepositoryLocId> keys) {
    if (keys.isEmpty()) {
      return Map.of();
    }
    Set<String> repositoryIds = new HashSet<>();
    Set<String> commitShas = new HashSet<>();
    for (GitRepositoryLocId key : keys) {
      repositoryIds.add(key.repositoryId);
      commitShas.add(key.commitSha);
    }
    Set<GitRepositoryLocId> wanted = new HashSet<>(keys);
    List<GitRepositoryLoc> rows =
        DbRetry.call(
            "loc summary bulk read",
            () ->
                QuarkusTransaction.requiringNew()
                    .call(
                        () ->
                            GitRepositoryLoc.<GitRepositoryLoc>list(
                                "repositoryId in ?1 and commitSha in ?2",
                                repositoryIds,
                                commitShas)),
            dbRetryDeadline);
    Map<GitRepositoryLocId, String> out = new HashMap<>();
    for (GitRepositoryLoc row : rows) {
      GitRepositoryLocId id = new GitRepositoryLocId(row.repositoryId, row.commitSha);
      if (wanted.contains(id)) {
        out.put(id, row.payload);
      }
    }
    return out;
  }

  /** Whether a summary is already stored. False when the store cannot answer — a rescan is cheap. */
  @ActivateRequestContext
  public boolean exists(String repositoryId, String commitSha) {
    return find(repositoryId, commitSha).isPresent();
  }

  /**
   * Stores one summary and prunes the repository down to {@link #KEPT_PER_REPOSITORY}. Insert-if-
   * absent: the payload is a pure function of the key, so losing the race to another writer changes
   * nothing and is not worth an error. A store that cannot take the write drops it with a warning —
   * the next reader scans again.
   */
  @ActivateRequestContext
  public void saveQuietly(String repositoryId, String commitSha, String payload) {
    try {
      QuarkusTransaction.requiringNew()
          .run(
              () -> {
                if (GitRepositoryLoc.findById(new GitRepositoryLocId(repositoryId, commitSha))
                    != null) {
                  return;
                }
                GitRepositoryLoc row = new GitRepositoryLoc();
                row.repositoryId = repositoryId;
                row.commitSha = commitSha;
                row.payload = payload;
                row.computedAt = Instant.now();
                row.persist();
                GitRepositoryLoc.getEntityManager().flush();
                prune(repositoryId);
              });
    } catch (RuntimeException e) {
      LOG.warnf(e, "could not store the loc summary of %s@%s; it will be rescanned", repositoryId,
          commitSha);
    }
  }

  /** The delete half of the write's transaction: everything past the newest N, oldest first out. */
  private static void prune(String repositoryId) {
    List<GitRepositoryLoc> stale =
        GitRepositoryLoc.<GitRepositoryLoc>find(
                "repositoryId = ?1 order by computedAt desc, commitSha", repositoryId)
            .range(KEPT_PER_REPOSITORY, Integer.MAX_VALUE - 1)
            .list();
    for (GitRepositoryLoc row : stale) {
      row.delete();
    }
  }
}
