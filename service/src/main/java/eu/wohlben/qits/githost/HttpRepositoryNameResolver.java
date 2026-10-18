package eu.wohlben.qits.githost;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.http.QitsHttpClients;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Typed;
import jakarta.inject.Inject;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * The <b>transitional fallback</b> behind {@link StoredRepositoryNameResolver}: it asks qits-projects,
 * which owns the alias table, over one GET — and is asked only when this host's own name index
 * misses. qits-1282 removes it once qits-projects has backfilled every name through {@code PUT
 * /git/:repoId/names}.
 *
 * <pre>
 *   GET &lt;qits.projects.name-resolver-url&gt;/{projectId}/repositories/by-name/{repoName}
 *   200 -&gt; {"repositoryId": "&lt;id&gt;"}
 *   404 -&gt; unknown project, or no repository under that name
 * </pre>
 *
 * <p><b>Absent config is a supported configuration and answers nothing.</b> With {@code
 * qits.projects.name-resolver-url} unset this returns empty without dialling anything, so the
 * name-addressed scheme serves only the names this host's own index holds, and every other name is
 * the 404 it was before this class existed.
 *
 * <p><b>A 404 is empty; everything else throws {@link RepositoryNameResolver.Unavailable}.</b> The
 * two are not the same answer and this adapter must not blur them: qits-projects' 404 means "no
 * repository under that name", which the routes turn into a 404 a git client caches as fact, while a
 * timeout, a refused connection, a non-200 or a body this cannot read mean the question was never
 * answered — a 503. Answering those empty is exactly the shape of {@code fe26a6c}, where an
 * unreachable database read as "no such repository" and every caller downstream believed it. Same
 * stance as the GC pin ports ({@code CdHttpDeploymentPins}, {@code CiHttpDaemonPins}), which throw
 * because a run that cannot read a pin must delete nothing.
 *
 * <p><b>A found id is cached; a miss and an outage never are.</b> Every name-addressed git request
 * lands here — each workspace daemon's minutely {@code git fetch} across ~70 repositories, the
 * checkout followers, CI clones — and uncached that was a steady 1.5–2 lookups a second against
 * qits-projects for the same names and the same answers ({@code qits-168}). So a 200's id is held in
 * {@link RepositoryNameCache} for {@code qits.projects.name-resolver-cache-ttl} (ten minutes; zero
 * turns it off). A 404 is not held, so a repository created a moment ago resolves at once, and an
 * {@link RepositoryNameResolver.Unavailable} is not held, so an outage is never replayed from memory.
 *
 * <p>The price is a rename, which is why this used to cache nothing. A rename moves no bare — the id
 * a name resolved to is the same repository afterwards — so what a stale entry can do is bounded and
 * small: the OLD name keeps serving that same, unchanged repository, and a new repository created
 * under the old name is shadowed by it. {@code bus/RepositoryRenamedListener} evicts both names the
 * moment qits-projects announces {@code RepositoryRenamed}; that delivery is live-only, so a rename
 * announced while this instance was off the stream is closed by the TTL instead, at most one TTL
 * after the entry was written. The new name needs nothing in the common case — a name nobody had
 * yet was a 404, and a 404 was never cached.
 *
 * <p><b>{@link Typed} to its own class, so it is not a {@link RepositoryNameResolver} bean.</b> The
 * port's one bean is {@link StoredRepositoryNameResolver}, which injects this class by name as its
 * delegate; exposing it under the port type too would make {@code Instance<RepositoryNameResolver>}
 * ambiguous. It still implements the port, because what it answers — and the miss/outage line it
 * draws — is the port's contract. The test suite replaces it with {@code FakeRepositoryNameResolver},
 * an alternative of this same type, so no {@code @QuarkusTest} dials qits-projects.
 */
@ApplicationScoped
@Typed(HttpRepositoryNameResolver.class)
public class HttpRepositoryNameResolver implements RepositoryNameResolver {

  private static final Logger LOG = Logger.getLogger(HttpRepositoryNameResolver.class);

  /** Bounded like every other outbound call here: connect, then the request, and no retries. */
  private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);

  static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(5);

  /**
   * An <b>instance</b> field, not a static one, and that is a native-image constraint rather than a
   * style preference: a static {@code HttpClient} is built by the class initialiser, which under
   * GraalVM runs at image-build time, and native-image then refuses the image over an {@code
   * HttpClientFacade} in its heap. {@code @ApplicationScoped} still means one client per process.
   * Wrapped through {@link QitsHttpClients} so this lookup opens a CLIENT span and carries {@code
   * X-Qits-Caller} the way the REST client already does.
   */
  private HttpClient client;

  /**
   * Where qits-projects answers the lookup, up to but not including {@code /{projectId}}. The PATH
   * below it is qits-projects' own, exactly as with {@code qits.projects.intake-url}; only the host
   * part is a deployment decision (`http://qits-projects:&lt;port&gt;/projects/api/projects`).
   *
   * <p>An {@code Optional}, never a {@code String} with an empty default: SmallRye reads a
   * configured-empty value as <em>absent</em>, so a {@code String} injection of an unset or blank key
   * kills the packaged binary at boot with "Failed to load config value of type java.lang.String" —
   * the trap {@code MirrorUpstream.endpointOverride} documents and {@code PackagedProcessIT} caught.
   */
  @ConfigProperty(name = "qits.projects.name-resolver-url")
  Optional<String> resolverUrl;

  @Inject ObjectMapper objectMapper;
  @Inject QitsHttpClients clients;
  @Inject RepositoryNameCache cache;

  /**
   * Built here rather than at the field: {@link #clients} is itself injected, and a field
   * initializer runs before CDI populates one.
   */
  @PostConstruct
  void buildClient() {
    client = clients.wrap(HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build());
  }

  @Override
  public Optional<String> resolveRepositoryId(String projectId, String name) {
    String base = resolverUrl.map(String::trim).filter(u -> !u.isEmpty()).orElse(null);
    if (base == null || projectId == null || name == null) {
      return Optional.empty();
    }
    // GitHostRoutes strips the suffix already; belt and braces, because a `.git` that reached the
    // url would look up a name qits-projects never registered.
    String bare = name.endsWith(".git") ? name.substring(0, name.length() - 4) : name;
    Optional<String> cached = cache.get(projectId, bare);
    if (cached.isPresent()) {
      return cached;
    }
    String url =
        base + "/" + segment(projectId) + "/repositories/by-name/" + segment(bare);
    try {
      HttpResponse<String> response =
          client.send(
              HttpRequest.newBuilder(URI.create(url))
                  .timeout(REQUEST_TIMEOUT)
                  // The by-name endpoint is qits:system. This call stays on qits-net, never
                  // through the edge, so it presents the forward-auth headers the platform trusts
                  // in-network — the same pair qits-ci's listing clients send. Without them the
                  // lookup answers 401, which this resolver reads as an outage (503 to the git
                  // client), so every name-addressed clone or push is refused.
                  .header("X-Qits-User", "qits-githost")
                  .header("X-Qits-Roles", "qits:system")
                  .GET()
                  .build(),
              HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
      if (response.statusCode() == 404) {
        // The documented "no such name" answer, not a fault: nothing to log.
        return Optional.empty();
      }
      if (response.statusCode() != 200) {
        throw unavailable(url + " answered " + response.statusCode(), null);
      }
      // A JsonNode, never a bound DTO — the wire rule this repo keeps so nothing needs
      // @RegisterForReflection.
      JsonNode body = objectMapper.readTree(response.body());
      JsonNode id = body == null ? null : body.get("repositoryId");
      if (id == null || !id.isTextual() || id.asText().isBlank()) {
        throw unavailable(url + " answered a body with no repositoryId", null);
      }
      // Only here, after every way of not answering has thrown: the one answer worth keeping.
      cache.put(projectId, bare, id.asText());
      return Optional.of(id.asText());
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw unavailable(url + " interrupted", interrupted);
    } catch (RepositoryNameResolver.Unavailable alreadyReported) {
      throw alreadyReported;
    } catch (Exception e) {
      throw unavailable(url + " failed: " + e, e);
    }
  }

  /** One place to log the fault and one shape to report it in. */
  private static RepositoryNameResolver.Unavailable unavailable(String what, Throwable cause) {
    LOG.warnf("name lookup %s", what);
    return new RepositoryNameResolver.Unavailable("name lookup " + what, cause);
  }

  /** One path segment. {@code URLEncoder} is form encoding, so its {@code +} needs undoing. */
  private static String segment(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
  }
}
