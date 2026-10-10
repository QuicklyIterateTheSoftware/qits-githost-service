package eu.wohlben.qits.githost.contracts;

import au.com.dius.pact.consumer.dsl.DslPart;
import au.com.dius.pact.consumer.dsl.Matchers;
import au.com.dius.pact.consumer.dsl.PactBuilder;
import au.com.dius.pact.consumer.dsl.PactDslJsonBody;
import au.com.dius.pact.core.support.Json;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * <b>Another service's recorded answers, as this repository's consumer pacts read them</b> (ticket
 * qits-1149, after epic qits-546). The consumer-side twin of qits-maintenance-service's {@code
 * testing/contracts/GoldenMasters}, with two changes:
 *
 * <ul>
 *   <li><b>One instance per provider.</b> A provider's golden-master jar puts its tree at {@code
 *       golden-masters/} on the test classpath, and so may another provider's. The index is picked
 *       by its {@code provider} field, never by classpath order.
 *   <li><b>The pact binds only what the consumer reads.</b> An interaction names the body paths its
 *       code reads ({@code consumes}); the response holds those paths and nothing else, with the
 *       recorded values as examples. No paths means a status-only interaction, which needs no
 *       recording at all — only the provider state.
 * </ul>
 *
 * <p>Matchers, from the index's {@code frozen} lists: a path under {@code frozen.ids} gets a UUID
 * matcher, under {@code frozen.instants} an ISO-8601 regex, everything else a type match.
 */
public final class ProviderGoldenMasters {

  /** This repository, as every pact names its consumer. */
  public static final String CONSUMER = "qits-githost-service";

  private static final String ROOT = "golden-masters/";

  private static final String ISO_INSTANT =
      "^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}(:\\d{2}(\\.\\d{1,9})?)?(Z|[+-]\\d{2}:?\\d{2})$";

  private static final Pattern PARAM = Pattern.compile("\\{([A-Za-z0-9_]+)}");

  private static final Pattern SIMPLE_PATH = Pattern.compile("^\\$(\\.[A-Za-z0-9_]+)+$");

  private static final ObjectMapper MAPPER = new ObjectMapper();

  /** qits-projects' answers: index provider {@code qits-projects}, pact provider its repository. */
  public static final ProviderGoldenMasters PROJECTS =
      new ProviderGoldenMasters("qits-projects", "qits-projects-service");

  private final String indexProvider;
  private final String provider;
  private volatile JsonNode index;
  private volatile String indexUrl;

  public ProviderGoldenMasters(String indexProvider, String provider) {
    this.indexProvider = indexProvider;
    this.provider = provider;
  }

  /** The provider as a pact names it: the repository name. */
  public String provider() {
    return provider;
  }

  /** What made this service make the call: the {@code qits-trigger} reference. */
  public record Trigger(String kind, String key, String value) {

    public Trigger {
      Objects.requireNonNull(kind, "kind");
      Objects.requireNonNull(key, "key");
      Objects.requireNonNull(value, "value");
    }

    /** One of this service's own doors, by its operationId or, for a raw route, its route. */
    public static Trigger operation(String operationId) {
      return new Trigger("operation", "operationId", operationId);
    }

    Map<String, String> reference() {
      Map<String, String> ref = new LinkedHashMap<>();
      ref.put("kind", kind);
      ref.put("app", CONSUMER);
      ref.put(key, value);
      return ref;
    }
  }

  /** The interaction's description: the trigger first, so (description, state) stays unique. */
  public static String description(String operationId, Trigger trigger) {
    return trigger.value() + ": " + operationId;
  }

  /**
   * One call this consumer makes. {@code path} is the provider's template ({@code {param}}); every
   * param is filled from the provider state's frozen params, or from {@code literals} when the
   * state does not carry it (a value the consumer chooses, such as a name it looks up).
   */
  public record Call(
      String operationId, String method, String path, Map<String, String> literals, int status) {}

  /** The provider state's frozen example params; fails naming the state when the index has none. */
  public Map<String, String> params(String state) {
    Map<String, String> params = new LinkedHashMap<>();
    stateNode(state)
        .path("params")
        .fields()
        .forEachRemaining(e -> params.put(e.getKey(), e.getValue().asText()));
    return params;
  }

  /**
   * Add the V4 HTTP interaction for {@code call} in {@code state}, reached from {@code trigger},
   * binding only {@code consumes}. With paths, the response body comes from the recording of
   * {@code call.operationId()} in {@code state}; without, the interaction is status-only.
   */
  public PactBuilder interaction(
      PactBuilder builder,
      String state,
      Call call,
      Trigger trigger,
      DslPart requestBody,
      List<String> consumes) {
    Objects.requireNonNull(trigger, "trigger: every interaction names the entry point that makes it");
    Map<String, String> params = params(state);
    DslPart body = consumes.isEmpty() ? null : responseBody(state, call, consumes);
    Map<String, Object> references = new LinkedHashMap<>();
    Map<String, String> qitsCall = new LinkedHashMap<>();
    qitsCall.put("app", provider);
    qitsCall.put("operationId", call.operationId());
    references.put("qits-call", qitsCall);
    references.put("qits-trigger", trigger.reference());
    return builder.expectsToReceiveHttpInteraction(
        description(call.operationId(), trigger),
        http -> {
          http.state(state, new LinkedHashMap<String, Object>(params));
          http.withRequest(
              request -> {
                request.method(call.method());
                String example = substitute(call, params, false);
                String expression = substitute(call, params, true);
                // A path with no state param in it gets no generator: pact-jvm reads an expression
                // without ${...} as a context key and would send the request to /null.
                if (expression.contains("${")) {
                  request.path(Matchers.fromProviderState(expression, example));
                } else {
                  request.path(example);
                }
                return requestBody == null ? request : request.body(requestBody);
              });
          http.willRespondWith(
              response -> {
                response.status(call.status());
                if (body != null) {
                  response
                      .header("Content-Type", Matchers.regexp("application/json.*", "application/json"))
                      .body(body);
                }
                return response;
              });
          // pact-jvm 4.6's DSL has no setter for an arbitrary comment group, but the V4 model's
          // comments map is mutable and written verbatim.
          http.getInteraction().getComments().put("references", Json.toJson(references));
          return http;
        });
  }

  // --- the body ---------------------------------------------------------------------------------

  private DslPart responseBody(String state, Call call, List<String> consumes) {
    JsonNode op = operationNode(state, call.operationId());
    if (op.path("status").asInt() != call.status()) {
      throw new IllegalStateException(
          indexProvider + " golden master " + state + "/" + call.operationId() + " recorded status "
              + op.path("status").asInt() + ", the call expects " + call.status());
    }
    JsonNode recorded = read(op.path("file").asText());
    Set<String> ids = strings(op.path("frozen").path("ids"));
    Set<String> instants = strings(op.path("frozen").path("instants"));
    PactDslJsonBody root = new PactDslJsonBody();
    for (String path : consumes) {
      if (!SIMPLE_PATH.matcher(path).matches()) {
        throw new IllegalArgumentException(
            path + ": only $.field(.field)* paths are supported by ProviderGoldenMasters yet");
      }
      List<String> segments = List.of(path.substring(2).split("\\."));
      JsonNode value = recorded;
      for (String segment : segments) {
        value = value.path(segment);
      }
      if (value.isMissingNode() || value.isNull() || value.isContainerNode()) {
        throw new IllegalStateException(
            indexProvider + " golden master " + state + "/" + call.operationId() + " holds no leaf at "
                + path);
      }
      PactDslJsonBody parent = root;
      for (String segment : segments.subList(0, segments.size() - 1)) {
        parent = parent.object(segment);
      }
      String name = segments.get(segments.size() - 1);
      if (ids.contains(path)) {
        parent.uuid(name, value.asText());
      } else if (instants.contains(path)) {
        parent.stringMatcher(name, ISO_INSTANT, value.asText());
      } else if (value.isTextual()) {
        parent.stringType(name, value.asText());
      } else if (value.isNumber()) {
        parent.numberType(name, value.numberValue());
      } else {
        parent.booleanType(name, value.asBoolean());
      }
      for (int i = 0; i < segments.size() - 1; i++) {
        parent = (PactDslJsonBody) parent.closeObject();
      }
    }
    return root;
  }

  private static String substitute(Call call, Map<String, String> params, boolean expression) {
    Matcher m = PARAM.matcher(call.path());
    StringBuilder out = new StringBuilder();
    while (m.find()) {
      String name = m.group(1);
      String value;
      if (params.containsKey(name)) {
        value = expression ? "${" + name + "}" : params.get(name);
      } else if (call.literals().containsKey(name)) {
        value = call.literals().get(name);
      } else {
        throw new IllegalStateException(
            call.operationId() + ": path " + call.path() + " names {" + name
                + "}, which neither the state's params nor the call's literals hold");
      }
      m.appendReplacement(out, Matcher.quoteReplacement(value));
    }
    m.appendTail(out);
    return out.toString();
  }

  // --- reading the jar --------------------------------------------------------------------------

  private JsonNode operationNode(String state, String operationId) {
    for (JsonNode op : stateNode(state).path("operations")) {
      if (operationId.equals(op.path("operationId").asText())) {
        return op;
      }
    }
    throw new IllegalArgumentException(
        indexProvider + "'s golden masters record no operation " + operationId + " in state '"
            + state + "'");
  }

  private JsonNode stateNode(String state) {
    for (JsonNode node : index().path("states")) {
      if (state.equals(node.path("name").asText())) {
        return node;
      }
    }
    throw new IllegalArgumentException(
        indexProvider + "'s golden masters record no state '" + state + "'");
  }

  /** The index whose {@code provider} is ours, among every {@code golden-masters/index.json}. */
  private JsonNode index() {
    JsonNode loaded = index;
    if (loaded != null) {
      return loaded;
    }
    List<String> seen = new ArrayList<>();
    try {
      for (URL url : Collections.list(loader().getResources(ROOT + "index.json"))) {
        JsonNode candidate;
        try (InputStream in = url.openStream()) {
          candidate = MAPPER.readTree(in);
        }
        String owner = candidate.path("provider").asText();
        seen.add(owner);
        if (indexProvider.equals(owner)) {
          if (candidate.path("formatVersion").asInt() != 1) {
            throw new IllegalStateException(
                indexProvider + "'s golden-masters/index.json is formatVersion "
                    + candidate.path("formatVersion") + "; this reader reads formatVersion 1");
          }
          indexUrl = url.toExternalForm();
          index = candidate;
          return candidate;
        }
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    throw new IllegalStateException(
        "no golden-masters/index.json of " + indexProvider + " on the test classpath (found "
            + seen + ") — is its golden-masters jar a test dependency of this module?");
  }

  /** A file of the same tree as the index, resolved next to it rather than by classpath order. */
  private JsonNode read(String file) {
    index();
    String base = indexUrl.substring(0, indexUrl.length() - "index.json".length());
    try (InputStream in = URI.create(base + file).toURL().openStream()) {
      return MAPPER.readTree(new String(in.readAllBytes(), StandardCharsets.UTF_8));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static ClassLoader loader() {
    ClassLoader own = ProviderGoldenMasters.class.getClassLoader();
    return own != null ? own : Thread.currentThread().getContextClassLoader();
  }

  private static Set<String> strings(JsonNode array) {
    Set<String> out = new java.util.LinkedHashSet<>();
    for (JsonNode e : array) {
      out.add(e.asText());
    }
    return out;
  }
}
