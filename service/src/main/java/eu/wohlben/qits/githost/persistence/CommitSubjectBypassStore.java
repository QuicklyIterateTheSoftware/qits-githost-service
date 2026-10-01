package eu.wohlben.qits.githost.persistence;

import eu.wohlben.qits.db.DbRetry;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.panache.common.Sort;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.control.ActivateRequestContext;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Writes and reads the commit-subject break-glass record ({@link CommitSubjectBypass}).
 *
 * <p>The write is called from {@code CommitSubjectHook} on a Vert.x worker inside {@code
 * ReceivePack.receive}, with no request context and no transaction bound, so it opens both for
 * itself — the shape {@link RepositoryProtectionStore} uses. It is patient ({@link
 * DbRetry#runInNewTx}) and then throws; the hook catches that and lets the push through anyway,
 * because a break-glass that stops working while the database is sick is not one.
 */
@ApplicationScoped
public class CommitSubjectBypassStore {

  /** One recorded use, as the read door answers it. */
  public record Use(
      UUID id,
      String repositoryId,
      String pusher,
      String reason,
      List<String> refs,
      List<String> commits,
      Instant usedAt) {}

  /** How long the write holds while the datasource is gone. Shared key; see the catalog. */
  @ConfigProperty(name = "qits.githost.db-retry-deadline", defaultValue = "15S")
  Duration dbRetryDeadline;

  /** Records one use. Throws when the database will not take it within the retry deadline. */
  @ActivateRequestContext
  public void record(
      String repositoryId, String pusher, String reason, List<String> refs, List<String> commits) {
    UUID id = UUID.randomUUID();
    Instant usedAt = Instant.now();
    DbRetry.runInNewTx(
        "commit-subject bypass record for git repository " + repositoryId,
        () -> {
          // The primary key is fixed outside the retried unit, so a re-run after an undecidable
          // commit collides rather than writing the same use twice.
          if (CommitSubjectBypass.findById(id) != null) {
            return;
          }
          CommitSubjectBypass row = new CommitSubjectBypass();
          row.id = id;
          row.repositoryId = repositoryId;
          row.pusher = pusher;
          row.reason = reason;
          row.refs = String.join("\n", refs);
          row.commits = String.join("\n", commits);
          row.usedAt = usedAt;
          row.persist();
          CommitSubjectBypass.getEntityManager().flush();
        },
        retryDeadline());
  }

  /** Every recorded use for one repository, newest first. Throws when the read cannot be made. */
  @ActivateRequestContext
  public List<Use> uses(String repositoryId) {
    return DbRetry.call(
        "commit-subject bypass read for git repository " + repositoryId,
        () ->
            QuarkusTransaction.requiringNew()
                .call(
                    () ->
                        CommitSubjectBypass.<CommitSubjectBypass>list(
                                "repositoryId", Sort.descending("usedAt"), repositoryId)
                            .stream()
                            .map(CommitSubjectBypassStore::toUse)
                            .toList()),
        retryDeadline());
  }

  private static Use toUse(CommitSubjectBypass row) {
    return new Use(
        row.id,
        row.repositoryId,
        row.pusher,
        row.reason,
        lines(row.refs),
        lines(row.commits),
        row.usedAt);
  }

  private static List<String> lines(String text) {
    return text == null || text.isEmpty() ? List.of() : List.of(text.split("\n"));
  }

  private Duration retryDeadline() {
    return dbRetryDeadline == null ? DbRetry.DEFAULT_DEADLINE : dbRetryDeadline;
  }
}
