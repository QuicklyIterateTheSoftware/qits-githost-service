package eu.wohlben.qits.githost;

import eu.wohlben.qits.eventstream.contracts.EventstreamPact;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

/**
 * <b>What qits-eventstream asks qits-events on qits-githost's behalf</b> (qits-1163): the outbox
 * publish of every event a push or a ref door announces ({@code bus.ScmEventAnnouncer}), pacted
 * by this service against the library version it pins and committed as {@code
 * pacts/qits-githost-service_qits-eventstream-javalib_qits-events-service.json}. It consumes no
 * event — its one listener, which evicted a name cache on qits-projects' {@code RepositoryRenamed},
 * went with that cache (qits-1215) — so no listener is declared; the guard fails naming any
 * listener or publisher in {@code target/classes} left out here.
 */
class EventstreamPactTest {

  static final EventstreamPact PACT =
      EventstreamPact.forConsumer("qits-githost-service")
          .publishes(true)
          .guard(Path.of("target/classes"));

  @TestFactory
  Stream<DynamicTest> rows() {
    return PACT.tests();
  }

  @Test
  void pactFile() {
    PACT.compareOrWritePactFile();
  }
}
