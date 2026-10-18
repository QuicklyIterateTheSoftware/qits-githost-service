package eu.wohlben.qits.githost.stories.support;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * The names the story repositories are known by — what qits-projects states to this host through
 * {@code PUT /git/<repoId>/names}, and what makes a PUBLIC clone url serve at all.
 *
 * <p>This host keys repositories by the id qits-projects mints, and keeps {@code (project, name) ->
 * id} itself: qits-projects owns the names and pushes every repository's set here, and {@code
 * /git/:projectId/:repoName} is answered from that index alone. So a git story's diagram has ONE
 * hop — the developer reaching this service — and no {@code qits-githost -> qits-projects} edge:
 * nothing on the clone, fetch, push or read path calls qits-projects. {@link StoryOrigin} states
 * each name as part of provisioning, playing qits-projects' client over the id-addressed door, and
 * {@link StoryAccessLog} drops that traffic like the rest of the storage plane.
 *
 * <h2>Why the aliases are a fixed table</h2>
 *
 * <p>The storage ids are readable literals rather than generated UUIDs. That costs nothing a UUID
 * would buy: {@link StoryOrigin} deletes and recreates each repository before its story, so a fixed
 * id is a repository at a known, empty state rather than whatever a previous build left — and a
 * readable id keeps the browser plane's labels ({@code GET /githost/api/repositories/story-browse ->
 * 200}) legible in the diagram, where a UUID would only ever render as {@code {id}}.
 */
public final class StoryNames {

  /**
   * How a diagram would name qits-projects — named here so a story can claim, by {@code
   * assertNoEdgesTo}, that this service never reached it.
   */
  public static final String PROJECTS_SERVICE = "qits-projects";

  /**
   * Every {@code repoName -> repoId} alias the story catalogue needs. One repository per story, so
   * no story can be reading what another one pushed — the diagrams stay legible and a class runs
   * green on its own.
   */
  public static final Map<String, String> ALIASES = aliases();

  /**
   * The one storage id with <b>no alias</b>, and deliberately: the browser plane addresses
   * repositories by storage id ({@code /githost/api/repositories/{repoId}}) because the SPA has
   * already resolved the public {@code (project, repoName)} spelling against qits-projects
   * client-side. So the browse story uses no name at all.
   */
  public static final String BROWSE_REPOSITORY_ID = "story-browse";

  private StoryNames() {}

  private static Map<String, String> aliases() {
    Map<String, String> table = new LinkedHashMap<>();
    table.put("hello-world", "story-clone");
    table.put("release-notes", "story-push");
    table.put("shared-notes", "story-pull");
    table.put("pipeline-config", "story-read");
    table.put("person-notes", "story-scope-person");
    table.put("ticket-work", "story-scope-agent");
    return Map.copyOf(table);
  }

  /** The storage id behind a public repository name, as qits-projects stated it. */
  public static String repositoryId(String repoName) {
    String id = ALIASES.get(repoName);
    if (id == null) {
      throw new IllegalArgumentException(
          "no alias for " + repoName + "; add it to StoryNames.ALIASES so the fixture states it");
    }
    return id;
  }

  /** The public name a storage id is stated under, or empty for one with none (the browse story's). */
  public static Optional<String> nameOf(String repoId) {
    return ALIASES.entrySet().stream()
        .filter(alias -> alias.getValue().equals(repoId))
        .map(Map.Entry::getKey)
        .findFirst();
  }
}
