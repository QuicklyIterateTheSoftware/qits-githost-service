package eu.wohlben.qits.githost;

import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import jakarta.enterprise.inject.Typed;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Test double for the <b>HTTP fallback</b> behind the shipped resolver — qits-projects' by-name
 * lookup, played by a map the test fills directly.
 *
 * <p>It stands in at the delegate's level, not at the port's: {@link StoredRepositoryNameResolver}
 * stays the {@link RepositoryNameResolver} bean in every {@code @QuarkusTest}, so the routes always
 * resolve through the real name index first, exactly as a deployment does, and only a name the index
 * does not hold reaches this map. A test that registers a name here is therefore exercising the
 * transitional fallback, and a test that states names through {@code PUT /git/:repoId/names}
 * exercises the index — both against the same routes.
 *
 * <p>An {@link Alternative} of {@link HttpRepositoryNameResolver}'s own type, enabled for the whole
 * suite by its {@link Priority}, so it replaces the HTTP adapter wherever that is injected and no
 * test dials qits-projects. It extends the adapter only to be of that type; nothing of the
 * superclass runs but its {@code @PostConstruct}, which builds an HTTP client this class never uses.
 * The adapter itself is covered on its own by {@link HttpRepositoryNameResolverTest}, outside CDI.
 */
@ApplicationScoped
@Alternative
@Priority(1)
@Typed({FakeRepositoryNameResolver.class, HttpRepositoryNameResolver.class})
public class FakeRepositoryNameResolver extends HttpRepositoryNameResolver {

  private final Map<String, String> aliases = new ConcurrentHashMap<>();

  private final AtomicBoolean unavailable = new AtomicBoolean();

  /** Registers {@code (projectId, name) -> repoId} in qits-projects, as a repository import would. */
  public void register(String projectId, String name, String repoId) {
    aliases.put(key(projectId, name), repoId);
  }

  /**
   * Makes every lookup throw, the way an unreachable qits-projects does. The port's two answers are
   * "no such name" and "could not ask", and only a test that can produce the second proves the
   * routes tell them apart.
   */
  public void beUnavailable(boolean unavailable) {
    this.unavailable.set(unavailable);
  }

  public void clear() {
    aliases.clear();
    unavailable.set(false);
  }

  @Override
  public Optional<String> resolveRepositoryId(String projectId, String name) {
    if (unavailable.get()) {
      throw new Unavailable("the fake resolver is standing in for an unreachable qits-projects");
    }
    return Optional.ofNullable(aliases.get(key(projectId, name)));
  }

  private static String key(String projectId, String name) {
    return projectId + "/" + name;
  }
}
