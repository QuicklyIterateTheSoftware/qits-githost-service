package eu.wohlben.qits.githost;

import jakarta.enterprise.context.ApplicationScoped;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * The positive answers {@link HttpRepositoryNameResolver} has had from qits-projects, kept for {@code
 * qits.projects.name-resolver-cache-ttl}. Its own bean rather than a field of the resolver so that
 * {@code bus/RepositoryRenamedListener} can evict from it: the resolver is a {@code @DefaultBean}
 * the test suite replaces, and a listener injecting it would have nothing to inject there.
 *
 * <p><b>Only a found id is ever held.</b> A 404 is not, so a repository created a second ago
 * resolves at once; an outage is not, so a 503 is never served from memory. What that buys is the
 * measured shape of the traffic: every workspace daemon fetches the same ~70 names every minute, and
 * the answer for all of them is the one it was ten minutes ago.
 *
 * <p>Keyed by the project segment <em>as the url carried it</em> — an id or a slug, both of which
 * qits-projects accepts — and the bare name. So one repository may sit here under two keys, which is
 * why eviction is by name across every project segment rather than by key.
 *
 * <p>Bounded crudely and deliberately: past {@link #MAX_ENTRIES} the whole map is dropped and refills
 * from misses. The estate has a few hundred names, so the bound is a guard against a client walking
 * made-up names that happen to exist, not a policy anyone should ever see working.
 */
@ApplicationScoped
public class RepositoryNameCache {

  static final int MAX_ENTRIES = 4096;

  /**
   * How long a found id is served without asking again. {@code 0} (or anything not positive) turns
   * the cache off: nothing is stored and every lookup dials. The default is spelled here as well as
   * in {@code application.properties} because a test profile's own properties file need not carry
   * it, and an unresolvable {@code Duration} would kill the boot.
   */
  @ConfigProperty(name = "qits.projects.name-resolver-cache-ttl", defaultValue = "10M")
  Duration ttl;

  /** The clock expiry reads; a field so a test can move time instead of sleeping through it. */
  LongSupplier nanoTime = System::nanoTime;

  private final Map<Key, Entry> entries = new ConcurrentHashMap<>();

  private record Key(String project, String name) {}

  private record Entry(String repositoryId, long expiresAtNanos) {}

  /** For CDI. */
  public RepositoryNameCache() {}

  /** For a test that builds the resolver, or the listener, by hand. */
  public RepositoryNameCache(Duration ttl, LongSupplier nanoTime) {
    this.ttl = ttl;
    this.nanoTime = nanoTime;
  }

  private boolean enabled() {
    return ttl != null && ttl.isPositive();
  }

  /** The cached id for {@code (project, name)}, or empty when none is held or it has expired. */
  public Optional<String> get(String project, String name) {
    if (!enabled()) {
      return Optional.empty();
    }
    Key key = new Key(project, name);
    Entry entry = entries.get(key);
    if (entry == null) {
      return Optional.empty();
    }
    if (nanoTime.getAsLong() - entry.expiresAtNanos() >= 0) {
      entries.remove(key, entry);
      return Optional.empty();
    }
    return Optional.of(entry.repositoryId());
  }

  /** Holds a FOUND id. There is no way to put a miss, and that is the point. */
  public void put(String project, String name, String repositoryId) {
    if (!enabled()) {
      return;
    }
    if (entries.size() >= MAX_ENTRIES) {
      entries.clear();
    }
    long expiresAt = nanoTime.getAsLong() + ttl.toNanos();
    entries.put(new Key(project, name), new Entry(repositoryId, expiresAt));
  }

  /**
   * Drops every entry under {@code name}, whatever project segment it was cached under.
   *
   * @return how many entries went
   */
  public int evictName(String name) {
    int before = entries.size();
    entries.keySet().removeIf(key -> key.name().equals(name));
    return before - entries.size();
  }

  /** Drops everything — the answer to a rename this side could not read. */
  public void clear() {
    entries.clear();
  }

  int size() {
    return entries.size();
  }
}
