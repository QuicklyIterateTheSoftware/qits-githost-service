package eu.wohlben.qits.githost.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.githost.GitRepositoryProvider;
import eu.wohlben.qits.githost.loc.LanguageLoc;
import eu.wohlben.qits.githost.loc.LocIndexer;
import eu.wohlben.qits.githost.loc.LocResponse;
import eu.wohlben.qits.githost.persistence.GitRepositoryLocId;
import eu.wohlben.qits.githost.persistence.RepositoryLocStore;
import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.ServerErrorException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.jboss.logging.Logger;

/**
 * {@code GET /githost/api/loc} — the lines-of-code summary of every repository's default branch, in
 * one request. The per-repository {@code /repositories/{repoId}/loc} answers one; a page that shows
 * many repositories asks here instead of once per repository.
 *
 * <p><b>Answered from the memo only.</b> A repository whose tip has a stored summary is {@code
 * COUNTED}. One without is {@code PENDING}: this call queues its scan on {@link LocIndexer} and
 * answers at once, so the request never waits for a scan, and a later call finds it counted. A
 * repository with no commit yet (an unborn {@code HEAD}) is {@code EMPTY}.
 *
 * <p><b>Project-agnostic.</b> Rows are keyed by the repository's storage id, the UUID qits-projects
 * mints; this host knows no project. A caller groups the rows by project with what qits-projects
 * tells it.
 *
 * <p><b>{@code repositoryId} narrows the answer</b>, repeatable. A named id this host does not hold
 * is left out rather than answered 404: the answer lists what this host holds, and a repository
 * deleted a moment ago is not an error for a page drawing many. An id that is not a valid slug is a
 * 400. Rows are sorted by id.
 *
 * <p><b>A failed read is a 5xx, never an empty list and never a row marked {@code PENDING}.</b> The
 * catalog, the repositories and the memo are all read strictly here — unlike the per-repository
 * endpoint, which can rescan on a memo it cannot read, this one has no fallback that would not lie.
 */
@Path("/loc")
@Produces(MediaType.APPLICATION_JSON)
public class LocListResource {

  private static final Logger LOG = Logger.getLogger(LocListResource.class);

  private static final String REPO_ID_PATTERN = "[A-Za-z0-9][A-Za-z0-9-]{0,63}";

  /** Whether a row's numbers are there. */
  public enum LocStatus {
    /** The default branch's tip is counted; {@code languages} holds the numbers. */
    COUNTED,
    /**
     * The tip is not counted yet, so the row carries the repository's newest stored count instead:
     * an older commit, named by {@code commitSha}. Rough and slightly outdated, never empty. The
     * tip's count is queued by this call.
     */
    STALE,
    /**
     * The repository was never counted; its tip's count is queued by this call. {@code languages}
     * is empty.
     */
    PENDING,
    /** The repository has no commit yet. {@code commitSha} is null, {@code languages} empty. */
    EMPTY
  }

  /**
   * One repository's default branch.
   *
   * @param commitSha the counted commit: the tip when {@code COUNTED} or {@code PENDING}, an older
   *     commit when {@code STALE}, null when {@code EMPTY}
   * @param languages one entry per language, largest total first; empty when {@code PENDING} or
   *     {@code EMPTY}
   */
  @RegisterForReflection
  public record LocEntry(
      String repositoryId, String commitSha, LocStatus status, List<LanguageLoc> languages) {}

  @RegisterForReflection
  public record LocListResponse(List<LocEntry> entries) {}

  @Inject GitRepositoryProvider repositories;
  @Inject RepositoryLocStore locStore;
  @Inject LocIndexer indexer;
  @Inject ObjectMapper mapper;

  @GET
  @Operation(
      operationId = "listLoc",
      summary = "Lines of code of every repository's default branch",
      description =
          "One row per repository this host holds, or per named repositoryId it holds. COUNTED rows"
              + " carry the default branch's numbers; STALE rows carry the newest stored numbers of"
              + " an older commit while the tip is counted; PENDING rows were never counted; EMPTY"
              + " rows have no commit yet. This call queues every tip that is not counted.")
  public LocListResponse list(@QueryParam("repositoryId") List<String> repositoryIds) {
    Set<String> named = null;
    if (repositoryIds != null && !repositoryIds.isEmpty()) {
      named = new TreeSet<>();
      for (String id : repositoryIds) {
        if (id == null || !id.matches(REPO_ID_PATTERN)) {
          throw new BadRequestException(
              Response.status(Response.Status.BAD_REQUEST)
                  .entity(new RepositoryBrowseResource.ErrorBody("repositoryId must match " + REPO_ID_PATTERN))
                  .build());
        }
        named.add(id);
      }
    }

    Set<String> ids = new TreeSet<>();
    try {
      for (String id : repositories.repositoryIds()) {
        if (id.matches(REPO_ID_PATTERN) && (named == null || named.contains(id))) {
          ids.add(id);
        }
      }
    } catch (Exception e) {
      throw unavailable("could not enumerate the git repositories", e);
    }

    // The tips first, then one memo read for all of them.
    Map<String, String> tips = new LinkedHashMap<>();
    for (String id : ids) {
      try (Repository repo = repositories.open(id)) {
        if (repo == null) {
          continue; // deleted between the enumeration and now
        }
        tips.put(id, tipOf(repo));
      } catch (Exception e) {
        throw unavailable("could not read the default branch of repository " + id, e);
      }
    }

    List<GitRepositoryLocId> wanted = new ArrayList<>();
    tips.forEach(
        (id, sha) -> {
          if (sha != null) {
            wanted.add(new GitRepositoryLocId(id, sha));
          }
        });
    Map<GitRepositoryLocId, String> stored;
    Map<String, RepositoryLocStore.StoredSummary> newest;
    try {
      stored = locStore.findAll(wanted);
      List<String> uncounted = new ArrayList<>();
      for (GitRepositoryLocId key : wanted) {
        if (!stored.containsKey(key)) {
          uncounted.add(key.repositoryId);
        }
      }
      newest = locStore.newest(uncounted);
    } catch (Exception e) {
      throw unavailable("could not read the stored lines-of-code summaries", e);
    }

    List<LocEntry> entries = new ArrayList<>(tips.size());
    for (Map.Entry<String, String> tip : tips.entrySet()) {
      String id = tip.getKey();
      String sha = tip.getValue();
      if (sha == null) {
        entries.add(new LocEntry(id, null, LocStatus.EMPTY, List.of()));
        continue;
      }
      String payload = stored.get(new GitRepositoryLocId(id, sha));
      LocStatus status = LocStatus.COUNTED;
      String counted = sha;
      if (payload == null) {
        indexer.enqueue(id, sha);
        RepositoryLocStore.StoredSummary older = newest.get(id);
        if (older == null) {
          entries.add(new LocEntry(id, sha, LocStatus.PENDING, List.of()));
          continue;
        }
        status = LocStatus.STALE;
        counted = older.commitSha();
        payload = older.payload();
      }
      try {
        LocResponse summary = mapper.readValue(payload, LocResponse.class);
        entries.add(new LocEntry(id, counted, status, summary.languages()));
      } catch (Exception e) {
        throw unavailable("could not read the stored summary of " + id + "@" + counted, e);
      }
    }
    return new LocListResponse(entries);
  }

  /** The commit the default branch names, or null for an unborn {@code HEAD}. */
  private static String tipOf(Repository repo) throws java.io.IOException {
    Ref head = repo.exactRef(Constants.HEAD);
    if (head == null) {
      return null;
    }
    ObjectId id = head.getObjectId();
    return id == null ? null : id.name();
  }

  private ServerErrorException unavailable(String what, Exception cause) {
    LOG.error(what, cause);
    return new ServerErrorException(what, Response.Status.INTERNAL_SERVER_ERROR, cause);
  }
}
