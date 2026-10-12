package eu.wohlben.qits.githost.bus;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.eventstream.QitsRawEventListener;
import eu.wohlben.qits.eventstream.control.EventFrame;
import eu.wohlben.qits.githost.RepositoryNameCache;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Set;
import org.jboss.logging.Logger;

/**
 * Evicts {@link RepositoryNameCache} on qits-projects' {@code RepositoryRenamed}, so a rename is seen
 * by the name-addressed scheme at once rather than one TTL later. The first thing this service
 * consumes from the bus; it published only until now.
 *
 * <p><b>Raw and live-only, on purpose.</b> Not a {@code QitsDurableEventListener}: what this keeps
 * current is an in-memory map, so there is nothing a missed event leaves behind that a restart has
 * not already thrown away, and nothing a replay from the epoch would rebuild. A durable consumer
 * would buy claim rows and a watermark on the eventstream datasource to guard a cache the TTL already
 * bounds. A rename announced while the stream was down is therefore not seen here at all, and the
 * resolver's TTL is what closes it — the staleness {@code HttpRepositoryNameResolver} documents.
 *
 * <p><b>Raw rather than typed</b>, because typed means binding a transcribed {@code RepositoryRenamed}
 * record, and a bound record is one more native-image registration in {@link EventWireReflection}.
 * Two string fields are read, so the payload is walked as a {@link JsonNode} — the wire rule the
 * resolver keeps for the same reason. {@link EventFrame} carries its own registration in the library.
 *
 * <p><b>Evicted by name, both names, across every project.</b> The cache is keyed by the project
 * segment as the url carried it, which may be the project's id or its slug; the event names the id.
 * Dropping every entry under either name costs at most a handful of re-lookups and cannot miss. A
 * payload this cannot read clears the whole cache instead: a rename happened and which one is
 * unknown, so the only safe forgetting is all of it.
 */
@ApplicationScoped
public class RepositoryRenamedListener implements QitsRawEventListener {

  private static final Logger LOG = Logger.getLogger(RepositoryRenamedListener.class);

  /** qits-projects' event name. It publishes no vocabulary jar, so the string is the contract. */
  static final String EVENT_NAME = "RepositoryRenamed";

  @Inject RepositoryNameCache names;

  @Inject ObjectMapper objectMapper;

  @Override
  public Set<String> signatures() {
    return Set.of(EVENT_NAME);
  }

  @Override
  public void onFrame(EventFrame frame) {
    if (!EVENT_NAME.equals(frame.name())) {
      return;
    }
    String oldName;
    String newName;
    try {
      JsonNode payload = objectMapper.readTree(frame.payload());
      oldName = text(payload, "oldName");
      newName = text(payload, "newName");
    } catch (Exception unreadable) {
      oldName = null;
      newName = null;
    }
    if (oldName == null && newName == null) {
      names.clear();
      LOG.warnf(
          "%s %s names no repository this service could read, so every cached name is dropped",
          EVENT_NAME, frame.id());
      return;
    }
    int evicted =
        (oldName == null ? 0 : names.evictName(oldName))
            + (newName == null ? 0 : names.evictName(newName));
    LOG.debugf(
        "%s %s: %s -> %s, %d cached name(s) dropped",
        EVENT_NAME, frame.id(), oldName, newName, evicted);
  }

  private static String text(JsonNode payload, String field) {
    JsonNode value = payload == null ? null : payload.get(field);
    return value == null || !value.isTextual() || value.asText().isBlank() ? null : value.asText();
  }
}
