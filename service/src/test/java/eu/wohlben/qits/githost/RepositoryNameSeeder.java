package eu.wohlben.qits.githost;

import eu.wohlben.qits.githost.persistence.RepositoryNameStore;
import io.quarkus.arc.ClientProxy;
import io.quarkus.test.junit.QuarkusMock;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Test helper that states repository names into the <b>real</b> name index ({@link
 * RepositoryNameStore}), the way qits-projects does through {@code PUT /git/:repoId/names} — so a
 * test that names a repository exercises exactly the path a deployment resolves through: the
 * shipped {@link StoredRepositoryNameResolver} over the store, and nothing else.
 *
 * <p><b>{@link #register} adds one name; the store replaces a whole set.</b> A statement is a
 * repository's complete name set at a newer revision, so this helper remembers what it has stated
 * per repository and restates the union, at a revision above anything a test wrote by hand. A name
 * stated for one repository moves away from another, in the store and here alike. Every project is
 * given the slug {@code slug-<projectId>}, which the slug's uniqueness needs and no test relies on.
 *
 * <p><b>{@link #beUnavailable} severs the store</b> — the {@link GitHostNamesTest.SeveredStore}
 * whose every lookup throws the way a lost database does — so a test can tell "no such name" (404)
 * from "could not ask" (503). It is a {@link QuarkusMock}, which outlives the test method that
 * installed it: turn it back off in a {@code finally} or an {@code @AfterEach}.
 */
@ApplicationScoped
public class RepositoryNameSeeder {

  @Inject RepositoryNameStore store;

  /** What this helper has stated, per repository id: its project and its names. */
  private final Map<String, Stated> stated = new ConcurrentHashMap<>();

  /** Above every revision a test states by hand, and increasing across the whole suite. */
  private final AtomicLong revision = new AtomicLong(System.currentTimeMillis() * 1000);

  /** The real store, captured the first time a severed one is installed over it. */
  private volatile RepositoryNameStore real;

  private record Stated(String projectId, Set<String> names) {}

  /** States {@code (projectId, name) -> repoId} in the index, keeping the repository's other names. */
  public synchronized void register(String projectId, String name, String repoId) {
    // A name is held by one repository of a project: the store moves it, so forget it elsewhere too.
    stated.forEach(
        (other, held) -> {
          if (!other.equals(repoId) && held.projectId().equals(projectId)) {
            held.names().remove(name);
          }
        });
    Stated mine = stated.get(repoId);
    if (mine == null || !mine.projectId().equals(projectId)) {
      mine = new Stated(projectId, new TreeSet<>());
      stated.put(repoId, mine);
    }
    mine.names().add(name);
    store.apply(
        repoId,
        projectId,
        "slug-" + projectId,
        new ArrayList<>(mine.names()),
        revision.incrementAndGet());
  }

  /**
   * Makes every name lookup throw, the way an unreadable database does — or puts the real store
   * back. The port's two answers are "no such name" and "could not ask", and only a test that can
   * produce the second proves the routes tell them apart.
   */
  public void beUnavailable(boolean unavailable) {
    if (unavailable) {
      if (real == null) {
        real = ClientProxy.unwrap(store);
      }
      QuarkusMock.installMockForType(new GitHostNamesTest.SeveredStore(), RepositoryNameStore.class);
    } else if (real != null) {
      QuarkusMock.installMockForType(real, RepositoryNameStore.class);
    }
  }

  /** Forgets what this helper stated and puts the real store back; the rows themselves stay. */
  public void clear() {
    stated.clear();
    beUnavailable(false);
  }
}
