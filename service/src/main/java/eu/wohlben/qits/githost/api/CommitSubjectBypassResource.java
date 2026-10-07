package eu.wohlben.qits.githost.api;

import eu.wohlben.qits.githost.persistence.CommitSubjectBypassStore;
import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.ServerErrorException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jboss.logging.Logger;

/**
 * {@code GET /githost/api/repositories/{repoId}/commit-subject-bypasses} — every recorded use of
 * the commit-subject break-glass ({@code -o qits.subject-bypass=<reason>}) on one repository,
 * newest first. See {@code CommitSubjectHook}.
 *
 * <p>Readable by {@code qits:admin}, {@code qits:admin-agent}, {@code qits:system} and {@code
 * qits:agent} — the {@code githost-browse} path policy's roles, restated on the method so the door
 * does not depend on the policy alone. {@code qits:admin-agent} is admitted too (qits-628
 * follow-up): an ADMIN workspace's coding agent carries it alongside {@code qits:agent}, and for now
 * it may use everything {@code qits:admin} may use. A repository with no recorded use, or one this
 * host does not hold, answers an empty list: the record outlives the repository on purpose. A failed
 * read is a 500, never an empty list.
 */
@Path("/repositories/{repoId}/commit-subject-bypasses")
@Produces(MediaType.APPLICATION_JSON)
public class CommitSubjectBypassResource {

  private static final Logger LOG = Logger.getLogger(CommitSubjectBypassResource.class);

  private static final String REPO_ID_PATTERN = "[A-Za-z0-9][A-Za-z0-9-]{0,63}";

  @Inject CommitSubjectBypassStore bypasses;

  /** One recorded use. {@code commits} are the full shas the guard would have refused. */
  @RegisterForReflection
  public record BypassRecord(
      UUID id,
      String repositoryId,
      String pusher,
      String reason,
      List<String> refs,
      List<String> commits,
      Instant usedAt) {}

  @RegisterForReflection
  public record BypassesResponse(List<BypassRecord> bypasses) {}

  @GET
  @RolesAllowed({"qits:admin", "qits:admin-agent", "qits:system", "qits:agent"})
  public BypassesResponse list(@PathParam("repoId") String repoId) {
    if (repoId == null || !repoId.matches(REPO_ID_PATTERN)) {
      throw new BadRequestException("repoId must match " + REPO_ID_PATTERN);
    }
    List<CommitSubjectBypassStore.Use> uses;
    try {
      uses = bypasses.uses(repoId);
    } catch (Exception e) {
      LOG.error("could not read the commit-subject bypasses of " + repoId, e);
      throw new ServerErrorException(
          "could not read the commit-subject bypasses", Response.Status.INTERNAL_SERVER_ERROR, e);
    }
    return new BypassesResponse(
        uses.stream()
            .map(
                u ->
                    new BypassRecord(
                        u.id(), u.repositoryId(), u.pusher(), u.reason(), u.refs(), u.commits(),
                        u.usedAt()))
            .toList());
  }
}
