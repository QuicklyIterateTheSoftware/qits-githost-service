package eu.wohlben.qits.githost;

import eu.wohlben.qits.githost.persistence.RepositoryNameStore;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Optional;
import org.jboss.logging.Logger;

/**
 * The shipped {@link RepositoryNameResolver}: it answers from this host's own name index ({@link
 * RepositoryNameStore}) and from nothing else. qits-projects owns the names and pushes every
 * repository's name set here through {@code PUT /git/:repoId/names}; this is the copy the git host
 * answers from.
 *
 * <p><b>The public clone url does not depend on qits-projects being up.</b> The lookup every clone,
 * fetch and push of {@code /git/:projectId/:repoName} makes is served from this service's own
 * database, with no call out. The first segment may be the project's id or its slug ({@link
 * RepositoryNameStore#resolve}).
 *
 * <p><b>A miss is the answer.</b> A name the index does not hold is a name no repository carries —
 * empty, a 404 at the routes.
 *
 * <p><b>A database that cannot be asked is an outage, not a miss.</b> The store throws rather than
 * answering empty, and that is turned into {@link RepositoryNameResolver.Unavailable} here — a 503
 * at the routes, which a git client retries instead of recording the repository as gone.
 */
@ApplicationScoped
public class StoredRepositoryNameResolver implements RepositoryNameResolver {

  private static final Logger LOG = Logger.getLogger(StoredRepositoryNameResolver.class);

  @Inject RepositoryNameStore store;

  @Override
  public Optional<String> resolveRepositoryId(String projectId, String name) {
    if (projectId == null || name == null) {
      return Optional.empty();
    }
    try {
      return store.resolve(projectId, name);
    } catch (RuntimeException e) {
      LOG.warnf("name index read %s/%s failed: %s", projectId, name, e.toString());
      throw new RepositoryNameResolver.Unavailable(
          "the name index could not be read for " + projectId + "/" + name, e);
    }
  }
}
