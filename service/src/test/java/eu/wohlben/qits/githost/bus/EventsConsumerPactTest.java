package eu.wohlben.qits.githost.bus;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import au.com.dius.pact.consumer.ConsumerPactRunnerKt;
import au.com.dius.pact.consumer.PactVerificationResult;
import au.com.dius.pact.consumer.dsl.PactBuilder;
import au.com.dius.pact.consumer.dsl.PactDslJsonBody;
import au.com.dius.pact.consumer.model.MockProviderConfig;
import au.com.dius.pact.core.model.PactSpecVersion;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.eventstream.QitsEvent;
import eu.wohlben.qits.eventstream.control.CanonicalJson;
import eu.wohlben.qits.eventstream.control.EventEnvelope;
import eu.wohlben.qits.eventstream.control.EventsPublisher;
import eu.wohlben.qits.githost.contracts.ProviderGoldenMasters;
import eu.wohlben.qits.githost.contracts.ProviderGoldenMasters.Call;
import eu.wohlben.qits.githost.contracts.ProviderGoldenMasters.Trigger;
import eu.wohlben.qits.githost.events.SCMDeleteBranch;
import eu.wohlben.qits.githost.events.SCMDeleteTag;
import eu.wohlben.qits.githost.events.SCMPublishCommit;
import eu.wohlben.qits.githost.events.SCMPublishTag;
import java.lang.reflect.Field;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

/**
 * <b>The consumer half of the qits-events contract</b> (ticket qits-1149) — WAITING on the
 * provider.
 *
 * <p>A push, and every ref door of {@code RepositoryRefsResource}, publishes {@code
 * SCMPublishCommit}, {@code SCMPublishTag}, {@code SCMDeleteBranch} or {@code SCMDeleteTag} through
 * the qits-eventstream library: {@link EventsPublisher#put} sends {@code PUT
 * /events/api/events/{id}} to qits-events (operation {@code publish}). The library reads the status
 * only: 200/201 is delivered, 400 is a reused id, anything else is retried by the outbox sweeper.
 * So every row is status-only, and its request body is what this repository really sends: the
 * library's own envelope of a real event, matched by type, its {@code name} exactly.
 *
 * <p>qits-events records neither state yet, and its golden-master jar is not a test dependency of
 * this module. When it records them: add {@code eu.wohlben.qits:qits-events-golden-masters}, enable
 * these tests, and add a pact file and the {@code qits-events-service} entry under {@code
 * contracts.pacts} in {@code .config/qits/release.yml}.
 */
class EventsConsumerPactTest {

  static {
    System.setProperty("pact_do_not_track", "true");
  }

  static final ProviderGoldenMasters EVENTS =
      new ProviderGoldenMasters("qits-events", "qits-events-service");

  /** NOT recorded yet: a fresh id, which {@code publish} stores and answers 200 or 201. */
  static final String NO_EVENT_WITH_THE_GIVEN_ID = "no event with the given id";

  /** NOT recorded yet: the id is taken by an event with other content; {@code publish} answers 400. */
  static final String AN_EVENT_WITH_THE_GIVEN_ID = "an event with the given id";

  static final String PUBLISH = "publish";

  static final String PATH = "/events/api/events/{id}";

  static final String DELIVERED_REASON =
      "needs provider state '" + NO_EVENT_WITH_THE_GIVEN_ID + "' for " + PUBLISH
          + " in qits-events-service";

  static final String REJECTED_REASON =
      "needs provider state '" + AN_EVENT_WITH_THE_GIVEN_ID + "' for " + PUBLISH
          + " in qits-events-service";

  static final Trigger PUSH = Trigger.operation("POST /git/{projectId}/{repoName}/git-receive-pack");

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private static final Instant AT = Instant.parse("2026-10-10T08:00:00Z");

  private static final String REPO_ID = "00000000-0000-4000-8000-000000000002";
  private static final String PROJECT_ID = "00000000-0000-4000-8000-000000000001";
  private static final String SHA = "019f0d272475dfef0ace655cc6d8672c8cd9c80c";
  private static final String OLD_SHA = "e447df8a0cabaeafedbf4c0235e013bb29697cd9";

  /** One event of each kind a push announces. */
  static List<QitsEvent> events() {
    return List.of(
        new SCMPublishCommit(
            UUID.randomUUID(), REPO_ID, PROJECT_ID, "qits-ci", "main", OLD_SHA, SHA,
            List.of(OLD_SHA), "qits", "qits@local", AT, AT, "feat(qits-1): a commit", AT),
        new SCMPublishTag(
            UUID.randomUUID(), REPO_ID, PROJECT_ID, "qits-ci", "2026.1010.80000", SHA, SHA,
            "qits", "qits@local", "release", true, AT),
        new SCMDeleteBranch(UUID.randomUUID(), REPO_ID, PROJECT_ID, "qits-ci", "feature", SHA, AT),
        new SCMDeleteTag(UUID.randomUUID(), REPO_ID, PROJECT_ID, "qits-ci", "old-tag", SHA, AT));
  }

  @Test
  @Disabled(DELIVERED_REASON)
  void eachEventAPushAnnouncesIsDelivered() {
    for (QitsEvent event : events()) {
      run(event, NO_EVENT_WITH_THE_GIVEN_ID, 200, EventsPublisher.Delivery::delivered);
    }
  }

  @Test
  @Disabled(REJECTED_REASON)
  void aReusedIdIsRejected() {
    run(events().get(0), AN_EVENT_WITH_THE_GIVEN_ID, 400, EventsPublisher.Delivery::rejected);
  }

  private static void run(
      QitsEvent event, String state, int status, Predicate<EventsPublisher.Delivery> expected) {
    String id = event.eventId().toString();
    EventEnvelope envelope = EventEnvelope.of(event);
    PactBuilder builder =
        new PactBuilder(ProviderGoldenMasters.CONSUMER, EVENTS.provider(), PactSpecVersion.V4);
    EVENTS.interaction(
        builder,
        state,
        new Call(PUBLISH, "PUT", PATH, Map.of("id", id), status),
        PUSH,
        requestBody(envelope),
        List.of());
    List<String> failures = new ArrayList<>();
    PactVerificationResult result =
        ConsumerPactRunnerKt.runConsumerTest(
            builder.toPact(),
            MockProviderConfig.createDefault(PactSpecVersion.V4),
            (mockServer, context) -> {
              EventsPublisher.Delivery delivery = publisher(mockServer.getUrl()).put(id, envelope);
              assertTrue(expected.test(delivery), envelope.name() + ": " + delivery);
              return null;
            });
    if (!(result instanceof PactVerificationResult.Ok)) {
      failures.add(envelope.name() + " [" + state + "]: " + result);
    }
    if (!failures.isEmpty()) {
      fail(String.join("\n", failures));
    }
  }

  /** The library's own envelope JSON, every member matched by type and {@code name} exactly. */
  static PactDslJsonBody requestBody(EventEnvelope envelope) {
    JsonNode json;
    try {
      json = MAPPER.readTree(CanonicalJson.envelope(envelope));
    } catch (java.io.IOException e) {
      throw new IllegalStateException(e);
    }
    PactDslJsonBody body = new PactDslJsonBody();
    Iterator<Map.Entry<String, JsonNode>> fields = json.fields();
    while (fields.hasNext()) {
      Map.Entry<String, JsonNode> field = fields.next();
      String name = field.getKey();
      JsonNode value = field.getValue();
      if ("name".equals(name)) {
        body.stringValue(name, value.asText());
      } else if (value.isNull()) {
        body.nullValue(name);
      } else if (value.isTextual()) {
        body.stringType(name, value.asText());
      } else if (value.isNumber()) {
        body.numberType(name, value.numberValue());
      } else if (value.isBoolean()) {
        body.booleanType(name, value.asBoolean());
      } else {
        throw new IllegalStateException("envelope member " + name + " is " + value.getNodeType());
      }
    }
    return body;
  }

  /** The library's publisher, its two config fields set as the CDI config would set them. */
  private static EventsPublisher publisher(String baseUrl) {
    EventsPublisher publisher = new EventsPublisher();
    set(publisher, "eventsUrl", baseUrl);
    set(publisher, "publishTimeout", Duration.ofSeconds(5));
    return publisher;
  }

  private static void set(Object target, String name, Object value) {
    try {
      Field field = target.getClass().getDeclaredField(name);
      field.setAccessible(true);
      field.set(target, value);
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException(e);
    }
  }
}
