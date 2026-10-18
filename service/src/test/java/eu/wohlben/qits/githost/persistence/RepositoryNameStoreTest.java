package eu.wohlben.qits.githost.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The name index against the real database: revision ordering, a name moving between repositories,
 * slugs, and the purge. Every case uses fresh ids and slugs, so the classes sharing the suite's
 * database cannot see one another's rows.
 */
@QuarkusTest
public class RepositoryNameStoreTest {

  @Inject RepositoryNameStore store;

  @Inject RepositoryPurge purge;

  private static String id() {
    return UUID.randomUUID().toString();
  }

  @Test
  public void aNewerRevisionReplacesTheWholeSet() {
    String repo = id();
    String project = id();
    String slug = "slug-" + id();

    RepositoryNameStore.Applied first = store.apply(repo, project, slug, List.of("b", "a"), 1);
    assertTrue(first.applied());
    assertEquals(List.of("a", "b"), first.state().names());
    assertEquals(Optional.of(repo), store.resolve(project, "a"));

    RepositoryNameStore.Applied second = store.apply(repo, project, slug, List.of("c"), 2);
    assertTrue(second.applied());
    assertEquals(List.of("c"), second.state().names());
    assertEquals(Optional.empty(), store.resolve(project, "a"), "a dropped name must stop resolving");
    assertEquals(Optional.of(repo), store.resolve(project, "c"));

    RepositoryNameStore.NamesState held = store.namesOf(repo).orElseThrow();
    assertEquals(new RepositoryNameStore.NamesState(project, slug, List.of("c"), 2), held);
  }

  @Test
  public void aStaleOrRepeatedRevisionIsIgnoredAndAnswersTheHeldState() {
    String repo = id();
    String project = id();
    String slug = "slug-" + id();
    store.apply(repo, project, slug, List.of("current"), 5);

    for (long stale : new long[] {5, 4, 0}) {
      RepositoryNameStore.Applied applied =
          store.apply(repo, project, slug, List.of("older-" + stale), stale);
      assertFalse(applied.applied(), "revision " + stale + " is not newer than 5");
      assertEquals(
          new RepositoryNameStore.NamesState(project, slug, List.of("current"), 5),
          applied.state());
    }
    assertEquals(Optional.of(repo), store.resolve(project, "current"));
    assertEquals(Optional.empty(), store.resolve(project, "older-4"));
  }

  @Test
  public void aStatedNameMovesFromTheRepositoryThatHeldIt() {
    String project = id();
    String slug = "slug-" + id();
    String first = id();
    String second = id();
    store.apply(first, project, slug, List.of("shared", "kept"), 1);

    store.apply(second, project, slug, List.of("shared"), 1);

    assertEquals(Optional.of(second), store.resolve(project, "shared"));
    assertEquals(Optional.of(first), store.resolve(project, "kept"));
    assertEquals(List.of("kept"), store.namesOf(first).orElseThrow().names());
    assertEquals(List.of("shared"), store.namesOf(second).orElseThrow().names());
  }

  @Test
  public void aNameIsScopedToItsProjectAndCaseSensitive() {
    String repo = id();
    String project = id();
    store.apply(repo, project, "slug-" + id(), List.of("Service"), 1);

    assertEquals(Optional.of(repo), store.resolve(project, "Service"));
    assertEquals(Optional.empty(), store.resolve(project, "service"));
    assertEquals(Optional.empty(), store.resolve(id(), "Service"));
  }

  @Test
  public void theProjectSlugResolvesLikeItsId() {
    String repo = id();
    String project = id();
    String slug = "slug-" + id();
    store.apply(repo, project, slug, List.of("svc"), 1);

    assertEquals(Optional.of(repo), store.resolve(slug, "svc"));
    assertEquals(Optional.of(repo), store.resolve(project, "svc"));
  }

  @Test
  public void anIdMatchWinsOverASlugMatch() {
    // A project whose SLUG is spelled like another project's ID: the id is the stable spelling.
    String byIdProject = id();
    String bySlugProject = id();
    String byIdRepo = id();
    String bySlugRepo = id();
    store.apply(byIdRepo, byIdProject, "slug-" + id(), List.of("svc"), 1);
    store.apply(bySlugRepo, bySlugProject, byIdProject, List.of("svc"), 1);

    assertEquals(Optional.of(byIdRepo), store.resolve(byIdProject, "svc"));
    assertEquals(Optional.of(bySlugRepo), store.resolve(bySlugProject, "svc"));
  }

  @Test
  public void aSlugStatedForANewProjectMovesToIt() {
    String slug = "slug-" + id();
    String oldProject = id();
    String newProject = id();
    String oldRepo = id();
    String newRepo = id();
    store.apply(oldRepo, oldProject, slug, List.of("svc"), 1);

    store.apply(newRepo, newProject, slug, List.of("svc"), 1);

    assertEquals(Optional.of(newRepo), store.resolve(slug, "svc"));
    assertEquals(Optional.of(oldRepo), store.resolve(oldProject, "svc"));
    assertNull(store.namesOf(oldRepo).orElseThrow().projectSlug(), "the old project lost its slug");
  }

  @Test
  public void anEmptySetIsAStatementToo() {
    String repo = id();
    String project = id();
    String slug = "slug-" + id();
    store.apply(repo, project, slug, List.of("gone-soon"), 1);

    RepositoryNameStore.Applied emptied = store.apply(repo, project, slug, List.of(), 2);

    assertTrue(emptied.applied());
    assertEquals(List.of(), emptied.state().names());
    assertEquals(Optional.empty(), store.resolve(project, "gone-soon"));
    assertEquals(2, store.namesOf(repo).orElseThrow().revision());
  }

  @Test
  public void aFirstStatementMayBeEmptyAndADuplicateNameIsOneName() {
    String repo = id();
    String project = id();
    assertTrue(store.namesOf(repo).isEmpty(), "no statement yet");

    assertTrue(store.apply(repo, project, "slug-" + id(), List.of(), 0).applied());
    assertEquals(0, store.namesOf(repo).orElseThrow().revision());

    RepositoryNameStore.Applied dup = store.apply(repo, project, "slug-" + id(), List.of("x", "x"), 1);
    assertEquals(List.of("x"), dup.state().names());
  }

  @Test
  public void thePurgeTakesTheNamesAndTheRevisionButLeavesTheSlug() {
    String repo = id();
    String project = id();
    String slug = "slug-" + id();
    store.apply(repo, project, slug, List.of("svc"), 3);

    purge.purge(repo);

    assertEquals(Optional.empty(), store.resolve(project, "svc"));
    assertTrue(store.namesOf(repo).isEmpty());
    assertEquals(
        slug,
        QuarkusTransaction.requiringNew()
            .call(() -> GitProject.<GitProject>findById(project).slug));
    // A recreated repository under the same id starts over: revision 1 applies again.
    assertTrue(store.apply(repo, project, slug, List.of("svc"), 1).applied());
  }
}
