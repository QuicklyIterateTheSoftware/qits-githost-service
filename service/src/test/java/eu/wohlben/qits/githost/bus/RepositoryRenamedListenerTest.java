package eu.wohlben.qits.githost.bus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.eventstream.control.EventFrame;
import eu.wohlben.qits.githost.RepositoryNameCache;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The rename listener on its own, plain JUnit: it is a frame in and evictions out, and the frame is
 * built here exactly as the dispatcher hands it over. The payload is qits-projects' {@code
 * RepositoryRenamed} as the canonical form spells it — the field names are the contract, since
 * qits-projects ships no vocabulary jar.
 */
class RepositoryRenamedListenerTest {

  private RepositoryNameCache cache;
  private RepositoryRenamedListener listener;

  @BeforeEach
  void build() {
    cache = new RepositoryNameCache(Duration.ofMinutes(10), () -> 0L);
    listener = new RepositoryRenamedListener();
    listener.names = cache;
    listener.objectMapper = new ObjectMapper();
    cache.put("p-7", "old-name", "repo-1");
    cache.put("my-project", "old-name", "repo-1");
    cache.put("p-7", "new-name", "repo-9");
    cache.put("p-7", "bystander", "repo-2");
  }

  private static EventFrame frame(String name, String payload) {
    return new EventFrame(
        "evt-1", name, Instant.parse("2026-10-12T10:00:00Z"), payload, null, null, "dev");
  }

  @Test
  void itAsksForRenamesAndNothingElse() {
    assertEquals(Set.of("RepositoryRenamed"), listener.signatures());
  }

  @Test
  void aRenameEvictsBothNamesUnderEveryProjectSegment() {
    listener.onFrame(
        frame(
            "RepositoryRenamed",
            "{\"newName\":\"new-name\",\"oldName\":\"old-name\",\"projectId\":\"p-7\","
                + "\"renamedAt\":\"2026-10-12T10:00:00Z\",\"repositoryId\":\"repo-1\"}"));
    assertEquals(Optional.empty(), cache.get("p-7", "old-name"));
    assertEquals(Optional.empty(), cache.get("my-project", "old-name"), "the slug-keyed entry too");
    assertEquals(Optional.empty(), cache.get("p-7", "new-name"));
    assertEquals(Optional.of("repo-2"), cache.get("p-7", "bystander"), "nothing else is touched");
  }

  @Test
  void aPayloadItCannotReadDropsEverything() {
    // A rename happened and which one is unknown: forgetting all of it is the only safe forgetting.
    listener.onFrame(frame("RepositoryRenamed", "}{ not json"));
    assertEquals(Optional.empty(), cache.get("p-7", "bystander"));

    cache.put("p-7", "bystander", "repo-2");
    listener.onFrame(frame("RepositoryRenamed", "{\"projectId\":\"p-7\"}"));
    assertEquals(Optional.empty(), cache.get("p-7", "bystander"), "no name in it is the same case");
  }

  @Test
  void anotherEventIsIgnored() {
    listener.onFrame(frame("SCMPublishCommit", "{\"oldName\":\"old-name\"}"));
    assertTrue(cache.get("p-7", "old-name").isPresent());
  }
}
