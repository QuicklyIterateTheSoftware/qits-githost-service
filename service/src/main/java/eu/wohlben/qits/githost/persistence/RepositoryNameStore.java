package eu.wohlben.qits.githost.persistence;

import eu.wohlben.qits.db.DbRetry;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.control.ActivateRequestContext;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicReference;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * The name index: {@code (project, name) -> repository id}, held by this host and filled by
 * qits-projects through {@code PUT /git/:repoId/names}. See {@code V6__repository_names.sql} for
 * the tables and the rules they keep.
 *
 * <p><b>qits-projects owns the names; this is the copy the git host answers from.</b> Each write
 * states one repository's WHOLE name set at a revision qits-projects increases, and {@link #apply}
 * replaces what is held only when that revision is newer — so a retried, duplicated or reordered
 * delivery is harmless, and the newest statement always wins.
 *
 * <h2>Threading and patience</h2>
 *
 * <p>The shape every store here has (see {@link CatalogRepository}): the routes' blocking handlers
 * bind no request context and no transaction, so each method activates its own context and opens its
 * own transaction. {@link #apply} is held through a lost connection by {@link DbRetry#runInNewTx},
 * which retries only the attempts that certainly did not commit; the reads by {@link DbRetry#call},
 * with the retry outside {@code requiringNew} so every attempt borrows a fresh connection.
 *
 * <p><b>A read that fails throws; it never answers empty.</b> {@link #resolve} is what the public
 * clone url is answered from, and "no such name" is a 404 a git client caches as "this repository is
 * gone" — so a database that could not be asked must reach the caller as a failure ({@code fe26a6c}).
 */
@ApplicationScoped
public class RepositoryNameStore {

  /** The revision {@link #namesOf} reports for a repository whose set was never stated. */
  public static final long NO_REVISION = -1;

  /** How long a read or the write holds while the datasource is gone. The suite shortens it. */
  @ConfigProperty(name = "qits.githost.db-retry-deadline", defaultValue = "15S")
  Duration dbRetryDeadline;

  /**
   * One repository's name set as this host holds it.
   *
   * @param projectId the project the set belongs to
   * @param projectSlug that project's slug, or {@code null} when no slug is held for it any more
   * @param names the names, sorted and distinct
   * @param revision the revision of the statement that wrote them
   */
  public record NamesState(String projectId, String projectSlug, List<String> names, long revision) {}

  /**
   * What {@link #apply} did: {@code applied} when the statement was newer and replaced the set,
   * otherwise not — and {@code state} is then what is held, which a caller can compare against.
   */
  public record Applied(NamesState state, boolean applied) {}

  /**
   * Replaces {@code repoId}'s name set with {@code names}, in {@code projectId} under {@code
   * projectSlug}, if {@code revision} is newer than the stored one.
   *
   * <p>One transaction: the repository's revision row is created if absent and then locked, so two
   * statements for one repository serialise; a stale one returns the held state unapplied. A newer
   * one drops the repository's names, takes over any of the stated names another repository of the
   * project holds, writes the set, stamps the revision, and moves the slug to this project (a slug is
   * held by one project at a time).
   *
   * <p>Concurrent statements for two DIFFERENT repositories claiming the same name are not
   * serialised by that lock; the loser fails on the primary key, and qits-projects' retry restates
   * it.
   */
  @ActivateRequestContext
  public Applied apply(
      String repoId, String projectId, String projectSlug, List<String> names, long revision) {
    AtomicReference<Applied> result = new AtomicReference<>();
    DbRetry.runInNewTx(
        "name set write for git repository " + repoId,
        () -> result.set(write(repoId, projectId, projectSlug, names, revision)),
        retryDeadline());
    return result.get();
  }

  /**
   * The whole database unit of {@link #apply}, and <b>nothing else</b> — the rule {@code
   * runInNewTx} rests on, since the retry re-runs it. Every statement is keyed, so a second run of
   * it is not a second effect.
   *
   * <p>The closing {@code flush} is load-bearing for the reason {@link
   * CatalogRepository#writePacks} gives: it moves the inserts out of the commit, where a lost
   * connection is undecidable, into the statement phase, where it is retried.
   */
  Applied write(
      String repoId, String projectId, String projectSlug, Collection<String> names,
      long revision) {
    EntityManager em = GitRepositoryNamesRevision.getEntityManager();
    Instant now = Instant.now();
    // Insert-if-absent, then lock: a row that does not exist yet cannot be locked, and two first
    // statements for one repository would otherwise both see "nothing stored" and both write.
    em.createNativeQuery(
            "insert into git_repository_names_revision"
                + " (repository_id, project_id, revision, updated_at)"
                + " values (:repositoryId, :projectId, :revision, :now)"
                + " on conflict (repository_id) do nothing")
        .setParameter("repositoryId", repoId)
        .setParameter("projectId", projectId)
        .setParameter("revision", NO_REVISION)
        .setParameter("now", now)
        .executeUpdate();
    GitRepositoryNamesRevision held =
        em.find(GitRepositoryNamesRevision.class, repoId, LockModeType.PESSIMISTIC_WRITE);
    if (held.revision >= revision) {
      return new Applied(read(held), false);
    }

    TreeSet<String> set = new TreeSet<>(names);
    GitRepositoryName.delete("repositoryId", repoId);
    if (!set.isEmpty()) {
      // The stated names are this repository's now, whichever repository of the project held them.
      GitRepositoryName.delete("projectId = ?1 and name in ?2", projectId, new ArrayList<>(set));
    }
    for (String name : set) {
      GitRepositoryName row = new GitRepositoryName();
      row.projectId = projectId;
      row.name = name;
      row.repositoryId = repoId;
      row.persist();
    }

    held.projectId = projectId;
    held.revision = revision;
    held.updatedAt = now;

    // The slug is the project's, held by one project at a time: another project stating it before
    // is the older statement, and gives it up.
    GitProject.delete("slug = ?1 and projectId <> ?2", projectSlug, projectId);
    GitProject project = GitProject.findById(projectId);
    if (project == null) {
      project = new GitProject();
      project.projectId = projectId;
    }
    project.slug = projectSlug;
    project.persist();

    em.flush();
    return new Applied(new NamesState(projectId, projectSlug, List.copyOf(set), revision), true);
  }

  /**
   * The repository id {@code name} resolves to in the project {@code projectSegment} names — by its
   * id or by its slug, the id winning when both match (an id is the stable spelling, and a slug only
   * the readable one).
   *
   * @return the repository id, or empty when this host holds no such name
   * @throws RuntimeException when the database could not be asked within the deadline — never an
   *     empty answer for a fault
   */
  @ActivateRequestContext
  public Optional<String> resolve(String projectSegment, String name) {
    return DbRetry.call(
        "name lookup " + projectSegment + "/" + name,
        () -> QuarkusTransaction.requiringNew().call(() -> lookup(projectSegment, name)),
        retryDeadline());
  }

  private Optional<String> lookup(String projectSegment, String name) {
    EntityManager em = GitRepositoryName.getEntityManager();
    List<String> byId =
        em.createQuery(
                "select n.repositoryId from GitRepositoryName n"
                    + " where n.projectId = :segment and n.name = :name",
                String.class)
            .setParameter("segment", projectSegment)
            .setParameter("name", name)
            .getResultList();
    if (!byId.isEmpty()) {
      return Optional.of(byId.get(0));
    }
    List<String> bySlug =
        em.createQuery(
                "select n.repositoryId from GitRepositoryName n, GitProject p"
                    + " where p.slug = :segment and n.projectId = p.projectId and n.name = :name",
                String.class)
            .setParameter("segment", projectSegment)
            .setParameter("name", name)
            .getResultList();
    return bySlug.isEmpty() ? Optional.empty() : Optional.of(bySlug.get(0));
  }

  /**
   * {@code repoId}'s name set as held, or empty when no set was ever stated for it — the {@code GET
   * /git/:repoId/names} answer. Throws, like {@link #resolve}, when the database cannot be asked.
   */
  @ActivateRequestContext
  public Optional<NamesState> namesOf(String repoId) {
    return DbRetry.call(
        "name set read for git repository " + repoId,
        () ->
            QuarkusTransaction.requiringNew()
                .call(
                    () -> {
                      GitRepositoryNamesRevision held =
                          GitRepositoryNamesRevision.findById(repoId);
                      return held == null ? Optional.<NamesState>empty() : Optional.of(read(held));
                    }),
        retryDeadline());
  }

  /** The held state behind a revision row: its names, sorted here so the order is the answer's. */
  private static NamesState read(GitRepositoryNamesRevision held) {
    TreeSet<String> names = new TreeSet<>();
    for (GitRepositoryName row :
        GitRepositoryName.<GitRepositoryName>list("repositoryId", held.repositoryId)) {
      names.add(row.name);
    }
    GitProject project = GitProject.findById(held.projectId);
    return new NamesState(
        held.projectId, project == null ? null : project.slug, List.copyOf(names), held.revision);
  }

  /**
   * The configured deadline, or the library's default for an instance nobody injected — the shape
   * every store here uses; see {@code CatalogRepository}.
   */
  private Duration retryDeadline() {
    return dbRetryDeadline == null ? DbRetry.DEFAULT_DEADLINE : dbRetryDeadline;
  }
}
