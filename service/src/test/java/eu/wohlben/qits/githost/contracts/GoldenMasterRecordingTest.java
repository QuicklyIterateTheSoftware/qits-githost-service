package eu.wohlben.qits.githost.contracts;

import static io.restassured.RestAssured.given;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * <b>Records qits-githost's provider golden masters</b> — {@code golden-masters/} at the repository
 * root, the source of the published golden-master artifact consumers write their pacts against.
 *
 * <p>For each (state, operation) pair in {@link #INTERACTIONS} it runs the state ({@link
 * ProviderStates}), calls the endpoint through REST-assured as the {@code %test} dev user, keeps
 * only the list entries the state created, freezes ids, instants and unique tokens ({@link
 * Freezer}) and renders {@code golden-masters/<state-slug>/<operationId>.json}; then it renders
 * {@code golden-masters/index.json} describing all of them.
 *
 * <p>It <b>compares by default</b> and fails with a unified diff per differing file — including a
 * committed {@code .json} no interaction produces any more. {@code -Dgolden.update=true} (or {@code
 * QITS_GOLDEN_UPDATE=true}) rewrites instead, and deletes such stale files; see {@link
 * GoldenFiles}.
 */
@QuarkusTest
class GoldenMasterRecordingTest {

  static final int FORMAT_VERSION = 1;
  static final String PROVIDER = "qits-githost";

  /**
   * One recorded interaction.
   *
   * @param path the route, a {@code ?query} after it recorded apart as the index's {@code query}
   * @param listFilteredTo the array (a {@code $.a.b} path) reduced to the entries the state created,
   *     or null — the index's {@code frozen.listFilteredTo}
   * @param sortedBy for an array the provider answers in no guaranteed order: {@code
   *     <$.path-to-array>:<field.path in each entry>}, sorted by that field's (seed-fixed) value
   *     before freezing, so ids are numbered in a stable order. Null when the order is the
   *     provider's own.
   * @param requestBody the JSON a write sends, recorded into the index as the operation's {@code
   *     body}; null for an operation that takes none (the served openapi says which, and the
   *     recording fails on a mismatch). A quoted {@code "{param}"} in it is expanded from the
   *     state's params and recorded unexpanded.
   * @param headers the response headers consumers read, recorded (frozen) into the index's {@code
   *     headers}
   * @param rawBody the answer is bytes, not JSON: no file is recorded and the body stays unbound
   */
  record Interaction(
      String state,
      String operationId,
      String method,
      String path,
      int status,
      String listFilteredTo,
      String sortedBy,
      String requestBody,
      List<String> headers,
      boolean rawBody) {

    /** A read: no request body, no header recorded, a JSON answer (or none). */
    Interaction(
        String state,
        String operationId,
        String method,
        String path,
        int status,
        String listFilteredTo,
        String sortedBy) {
      this(state, operationId, method, path, status, listFilteredTo, sortedBy, null, List.of(), false);
    }
  }

  /** The one response header the raw content reads carry: the commit the revision resolved to. */
  static final String COMMIT_SHA_HEADER = "Git-Commit-Sha";

  /** Who the release writes are made by, as qits-projects sends it. */
  private static final String AUTHOR =
      "\"author\":{\"name\":\"qits-projects\",\"email\":\"qits-projects@qits.internal\"}";

  private static final String COMMIT_BODY =
      "{\"ref\":\"refs/heads/main\",\"message\":\"release(2026.101.120000): stamp the version\","
          + "\"files\":{\"pom.xml\":\"<project>\\n  <version>2026.101.120000</version>\\n</project>\\n\"},"
          + AUTHOR
          + ",\"projectId\":\"{projectId}\",\"repoName\":\"{repoName}\"}";

  private static final String TAG_BODY =
      "{\"name\":\"2026.101.120000\",\"sha\":\"{sha}\",\"message\":\"release 2026.101.120000\","
          + AUTHOR
          + ",\"projectId\":\"{projectId}\",\"repoName\":\"{repoName}\"}";

  private static final String MERGE_BODY =
      "{\"target\":\"{target}\",\"sources\":[\"{source}\"],\"message\":\"fold the branch\","
          + AUTHOR
          + ",\"projectId\":\"{projectId}\",\"repoName\":\"{repoName}\",\"versionPins\":true}";

  private static final String NAMED = "/git/{projectId}/{repoName}";

  private static final String BY_ID = "/githost/api/repositories/{repositoryId}";

  static final List<Interaction> INTERACTIONS =
      List.of(
          new Interaction(
              ProviderStates.A_REPOSITORY_WITH_COUNTED_LINES,
              "listLoc",
              "GET",
              "/githost/api/loc",
              200,
              "$.entries",
              null),
          new Interaction(
              ProviderStates.A_REPOSITORY_NOT_COUNTED_YET,
              "listLoc",
              "GET",
              "/githost/api/loc",
              200,
              "$.entries",
              null),
          new Interaction(
              ProviderStates.A_REPOSITORY_WITH_NO_COMMIT,
              "listLoc",
              "GET",
              "/githost/api/loc",
              200,
              "$.entries",
              null),
          new Interaction(
              ProviderStates.TWO_REPOSITORIES_ONE_COUNTED,
              "listLoc",
              "GET",
              "/githost/api/loc",
              200,
              "$.entries",
              null),
          new Interaction(
              ProviderStates.A_REPOSITORY_COUNTED_AT_AN_OLDER_COMMIT,
              "listLoc",
              "GET",
              "/githost/api/loc",
              200,
              "$.entries",
              null),
          new Interaction(
              ProviderStates.A_REPOSITORY_WITH_COUNTED_LINES,
              "getLoc",
              "GET",
              BY_ID + "/loc",
              200,
              null,
              null),
          new Interaction(
              ProviderStates.NO_REPOSITORY_WITH_THE_GIVEN_ID,
              "getLoc",
              "GET",
              BY_ID + "/loc",
              404,
              null,
              null),
          // --- the repository lifecycle, id-addressed (qits-projects, qits-bootstrap-cli) -------
          write(
              ProviderStates.NO_REPOSITORY_WITH_THE_GIVEN_ID,
              "createRepository",
              "PUT",
              "/git/{repositoryId}",
              201,
              "{\"defaultBranch\":\"main\"}"),
          write(
              ProviderStates.A_REPOSITORY_EXISTS,
              "createRepository",
              "PUT",
              "/git/{repositoryId}",
              200,
              "{\"defaultBranch\":\"main\"}"),
          new Interaction(
              ProviderStates.A_REPOSITORY_EXISTS,
              "describeRepository",
              "GET",
              "/git/{repositoryId}",
              200,
              null,
              null),
          new Interaction(
              ProviderStates.NO_REPOSITORY_WITH_THE_GIVEN_ID,
              "describeRepository",
              "GET",
              "/git/{repositoryId}",
              404,
              null,
              null),
          write(
              ProviderStates.A_REPOSITORY_EXISTS,
              "deleteRepository",
              "DELETE",
              "/git/{repositoryId}",
              204,
              null),
          write(
              ProviderStates.NO_REPOSITORY_WITH_THE_GIVEN_ID,
              "deleteRepository",
              "DELETE",
              "/git/{repositoryId}",
              404,
              null),
          new Interaction(
              ProviderStates.TWO_REPOSITORIES,
              "listGitRepositories",
              "GET",
              "/git",
              200,
              "$.repositories",
              null),
          // --- the raw content reads, name-addressed (qits-ci, qits-maintenance, qits-projects,
          // qits-deployments): status and Git-Commit-Sha, plus the entries where the answer is JSON
          raw(
              ProviderStates.A_REPOSITORY_WITH_FILES_ON_MAIN,
              "getRootTree",
              NAMED + "/tree/{sha}",
              200,
              false),
          raw(
              ProviderStates.A_REPOSITORY_WITH_FILES_ON_MAIN,
              "getTree",
              NAMED + "/tree/{rev}/{directory}",
              200,
              false),
          raw(
              ProviderStates.A_REPOSITORY_WITH_FILES_ON_MAIN,
              "getBlob",
              NAMED + "/blob/{rev}/{path}",
              200,
              true),
          raw(
              ProviderStates.A_WRAPPER_REPOSITORY_WITH_A_SUBMODULE,
              "getTree",
              NAMED + "/tree/{rev}/{directory}",
              200,
              false),
          raw(
              ProviderStates.A_REPOSITORY_MISSING_THE_REQUESTED_COMMIT,
              "getRootTree",
              NAMED + "/tree/{sha}",
              404,
              true),
          raw(
              ProviderStates.A_REPOSITORY_MISSING_THE_REQUESTED_PATH,
              "getTree",
              NAMED + "/tree/{rev}/{directory}",
              404,
              true),
          raw(
              ProviderStates.A_REPOSITORY_MISSING_THE_REQUESTED_PATH,
              "getBlob",
              NAMED + "/blob/{rev}/{path}",
              404,
              true),
          // --- the REST reads and ref writes (qits-projects, qits-maintenance) ------------------
          new Interaction(
              ProviderStates.A_REPOSITORY_WITH_A_SIDE_BRANCH,
              "getRepository",
              "GET",
              BY_ID,
              200,
              null,
              null),
          new Interaction(
              ProviderStates.A_REPOSITORY_WITH_FILES_ON_MAIN,
              "getRepositoryTree",
              "GET",
              BY_ID + "/tree?rev={rev}",
              200,
              null,
              null),
          new Interaction(
              ProviderStates.A_REPOSITORY_WITH_FILES_ON_MAIN,
              "getRepositoryFile",
              "GET",
              BY_ID + "/file?rev={rev}&path={path}",
              200,
              null,
              null),
          new Interaction(
              ProviderStates.A_REPOSITORY_WHOSE_MAIN_CONTAINS_A_COMMIT,
              "containsCommit",
              "GET",
              BY_ID + "/contains?commit={commit}&in={in}",
              200,
              null,
              null),
          write(
              ProviderStates.A_REPOSITORY_WITH_FILES_ON_MAIN,
              "commitFiles",
              "POST",
              BY_ID + "/commits",
              200,
              COMMIT_BODY),
          write(
              ProviderStates.A_REPOSITORY_WITH_FILES_ON_MAIN,
              "createTag",
              "POST",
              BY_ID + "/tags",
              201,
              TAG_BODY),
          write(
              ProviderStates.A_REPOSITORY_WITH_A_SIDE_BRANCH,
              "deleteBranch",
              "DELETE",
              BY_ID + "/branches/{branch}?projectId={projectId}&repoName={repoName}",
              204,
              null),
          write(
              ProviderStates.A_REPOSITORY_WITHOUT_THE_GIVEN_BRANCH,
              "deleteBranch",
              "DELETE",
              BY_ID + "/branches/{branch}?projectId={projectId}&repoName={repoName}",
              404,
              null),
          write(
              ProviderStates.A_REPOSITORY_WITH_A_BRANCH_TO_FOLD,
              "mergeBranches",
              "POST",
              BY_ID + "/merges",
              200,
              MERGE_BODY),
          write(
              ProviderStates.A_REPOSITORY_WITH_A_BRANCH_THAT_CONFLICTS_WITH_MAIN,
              "mergeBranches",
              "POST",
              BY_ID + "/merges",
              409,
              MERGE_BODY));

  /** A write: {@code body} null for one that takes none. */
  private static Interaction write(
      String state, String operationId, String method, String path, int status, String body) {
    return new Interaction(
        state, operationId, method, path, status, null, null, body, List.of(), false);
  }

  /**
   * A raw content read. A 200 records {@value #COMMIT_SHA_HEADER}; {@code rawBody} for an answer
   * that is not JSON (the blob's bytes, an error's empty body).
   */
  private static Interaction raw(
      String state, String operationId, String path, int status, boolean rawBody) {
    return new Interaction(
        state,
        operationId,
        "GET",
        path,
        status,
        null,
        null,
        null,
        status == 200 ? List.of(COMMIT_SHA_HEADER) : List.of(),
        rawBody);
  }

  private static final ObjectMapper JSON = new ObjectMapper();
  private static final Pattern TEMPLATE_PARAM = Pattern.compile("\\{([^}]+)}");

  /** A param in a request body: a quoted {@code "{name}"}, so the JSON's own braces never match. */
  private static final Pattern BODY_PARAM = Pattern.compile("\"\\{([A-Za-z][A-Za-z0-9]*)}\"");

  @Inject ProviderStates states;

  @Test
  void goldenMastersMatchTheProvider() throws IOException {
    Path dir = GoldenFiles.repositoryRoot().resolve("golden-masters");
    boolean update = GoldenFiles.updating();
    List<String> failures = new ArrayList<>();
    Set<String> written = new TreeSet<>();

    // slug -> recorded state, sorted by slug; operations sorted by operationId below
    Map<String, ObjectNode> indexStates = new TreeMap<>();
    Map<String, Map<String, ObjectNode>> indexOperations = new TreeMap<>();

    Set<String> takesBody = operationsTakingABody();
    for (Interaction interaction : INTERACTIONS) {
      if ((interaction.requestBody() != null) != takesBody.contains(interaction.operationId())) {
        failures.add(
            interaction.operationId()
                + (interaction.requestBody() != null
                    ? " takes no request body, but the recording sends one: record null."
                    : " takes a request body, but the recording sends none."));
      }
      Recorded recorded = record(interaction);
      String slug = ProviderStates.slug(interaction.state());
      String file = slug + "/" + interaction.operationId() + ".json";

      ObjectNode state = indexStates.get(slug);
      if (state == null) {
        state = JsonNodeFactory.instance.objectNode();
        state.put("name", interaction.state());
        state.put("slug", slug);
        state.set("params", recorded.params());
        state.set("dependsOn", JsonNodeFactory.instance.arrayNode());
        indexStates.put(slug, state);
      } else if (!state.get("params").equals(recorded.params())) {
        failures.add("State '" + interaction.state() + "' froze to different params per operation");
      }

      ObjectNode operation = JsonNodeFactory.instance.objectNode();
      operation.put("operationId", interaction.operationId());
      operation.put("method", interaction.method());
      // The path is the route alone and the query its own object, as the consumers' pacts send it.
      int at = interaction.path().indexOf('?');
      operation.put("path", at < 0 ? interaction.path() : interaction.path().substring(0, at));
      if (at >= 0) {
        ObjectNode query = operation.putObject("query");
        for (String pair : interaction.path().substring(at + 1).split("&")) {
          int eq = pair.indexOf('=');
          query.put(
              URLDecoder.decode(eq < 0 ? pair : pair.substring(0, eq), StandardCharsets.UTF_8),
              eq < 0 ? "" : URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
        }
      }
      if (interaction.requestBody() != null) {
        operation.set("body", JSON.readTree(interaction.requestBody()));
      }
      operation.put("status", interaction.status());
      if (!recorded.headers().isEmpty()) {
        operation.set("headers", recorded.headers());
      }
      // No file for an answer with no JSON body: a 204, an empty error, a blob's bytes.
      if (recorded.body() == null) {
        operation.putNull("file");
      } else {
        operation.put("file", file);
      }
      ObjectNode frozen = operation.putObject("frozen");
      frozen.set("ids", strings(recorded.freezer().idPaths()));
      frozen.set("instants", strings(recorded.freezer().instantPaths()));
      frozen.set("strings", strings(recorded.freezer().stringPaths()));
      if (!recorded.freezer().shaPaths().isEmpty()) {
        // Additive, and only where a sha was frozen: every other entry stays as it was.
        frozen.set("shas", strings(recorded.freezer().shaPaths()));
      }
      if (!recorded.headers().isEmpty()) {
        frozen.set("headers", strings(new ArrayList<>(interaction.headers())));
      }
      if (interaction.listFilteredTo() == null) {
        frozen.putNull("listFilteredTo");
      } else {
        frozen.put("listFilteredTo", interaction.listFilteredTo());
      }
      if (indexOperations
              .computeIfAbsent(slug, k -> new TreeMap<>())
              .put(interaction.operationId(), operation)
          != null) {
        failures.add("Duplicate interaction " + file);
      }

      if (recorded.body() != null) {
        written.add(file);
        check(dir.resolve(file), GoldenJson.render(recorded.body()), update, failures);
      }
    }

    ObjectNode index = JsonNodeFactory.instance.objectNode();
    index.put("formatVersion", FORMAT_VERSION);
    index.put("provider", PROVIDER);
    ArrayNode stateArray = index.putArray("states");
    indexStates.forEach(
        (slug, state) -> {
          ArrayNode operations = state.putArray("operations");
          indexOperations.get(slug).values().forEach(operations::add);
          stateArray.add(state);
        });
    written.add("index.json");
    check(dir.resolve("index.json"), GoldenJson.render(index), update, failures);

    for (String stale : committedJson(dir)) {
      if (written.contains(stale)) {
        continue;
      }
      if (update) {
        Files.delete(dir.resolve(stale));
      } else {
        failures.add(
            dir.resolve(stale)
                + " is committed but no interaction records it any more — rerun with"
                + " -Dgolden.update=true to delete it.");
      }
    }

    if (!failures.isEmpty()) {
      throw new AssertionError(String.join("\n\n", failures));
    }
  }

  private static void check(Path golden, String actual, boolean update, List<String> failures) {
    String failure = GoldenFiles.check(golden, actual, update, UnaryOperator.identity());
    if (failure != null) {
      failures.add(failure);
    }
  }

  /** One interaction's frozen answer, its frozen params and what was frozen where. */
  record Recorded(JsonNode body, ObjectNode headers, ObjectNode params, Freezer freezer) {}

  private Recorded record(Interaction interaction) throws IOException {
    ProviderStates.Setup setup = states.setUp(interaction.state());
    Map<String, String> params = setup.params();

    var request = given();
    if (interaction.requestBody() != null) {
      request =
          request
              .contentType("application/json")
              .body(expand(interaction.requestBody(), params, BODY_PARAM));
    } else {
      // As a body-less call is sent: RestAssured would otherwise add a form content type.
      request = request.noContentType();
    }
    Response response =
        request.when().request(interaction.method(), expand(interaction.path(), params));
    String raw = response.asString();
    if (response.statusCode() != interaction.status()) {
      throw new AssertionError(
          interaction.method()
              + " "
              + interaction.path()
              + " in state '"
              + interaction.state()
              + "' answered "
              + response.statusCode()
              + ", expected "
              + interaction.status()
              + ": "
              + raw);
    }
    Freezer freezer = new Freezer().seed(params.values()).uniqueTokens(setup.uniqueTokens());
    ObjectNode frozenParams = JsonNodeFactory.instance.objectNode();
    params.forEach((k, v) -> frozenParams.put(k, freezer.freezeParam(v)));

    JsonNode body = null;
    if (!interaction.rawBody() && !raw.isBlank()) {
      body =
          freezer.freeze(
              recordable(JSON.readTree(raw), interaction, params.values(), setup.uniqueTokens()));
    }
    ObjectNode headers = JsonNodeFactory.instance.objectNode();
    for (String name : interaction.headers()) {
      String value = response.header(name);
      if (value == null) {
        throw new AssertionError(
            interaction.operationId() + " in state '" + interaction.state() + "' carries no "
                + name + " header");
      }
      headers.put(name, freezer.freezeHeader(value));
    }
    return new Recorded(body, headers, frozenParams, freezer);
  }

  /**
   * The answer reduced to what the state controls: the {@code listFilteredTo} array keeps only the
   * entries mentioning an id the state created (its param values), and a {@code sortedBy} array is
   * put in seed order — by the field's value with the state's unique tokens blanked out, since a
   * random token would otherwise decide where its entry sorts. Package-private for the machinery
   * test.
   */
  static JsonNode recordable(
      JsonNode body,
      Interaction interaction,
      Collection<String> createdIds,
      Collection<String> uniqueTokens) {
    JsonNode out = body.deepCopy();
    if (interaction.listFilteredTo() != null) {
      ArrayNode list = array(out, interaction.listFilteredTo());
      ArrayNode kept = JsonNodeFactory.instance.arrayNode();
      for (JsonNode entry : list) {
        String text = entry.toString();
        if (createdIds.stream().anyMatch(text::contains)) {
          kept.add(entry);
        }
      }
      list.removeAll();
      list.addAll(kept);
    }
    if (interaction.sortedBy() != null) {
      String[] parts = interaction.sortedBy().split(":", 2);
      ArrayNode list = array(out, parts[0]);
      List<JsonNode> entries = new ArrayList<>();
      list.forEach(entries::add);
      String[] field = parts[1].split("\\.");
      entries.sort(
          Comparator.comparing(
              entry -> {
                JsonNode node = entry;
                for (String f : field) {
                  node = node.path(f);
                }
                String key = node.asText();
                for (String token : uniqueTokens) {
                  key = key.replace(token, "");
                }
                return key;
              }));
      list.removeAll();
      list.addAll(entries);
    }
    return out;
  }

  /** The array at a {@code $.a.b} path — the only JSONPath shape the table uses. */
  private static ArrayNode array(JsonNode root, String path) {
    if (!path.startsWith("$.")) {
      throw new IllegalArgumentException("Only $.a.b paths are supported: " + path);
    }
    JsonNode node = root;
    for (String segment : path.substring(2).split("\\.")) {
      node = node.path(segment);
    }
    if (!node.isArray()) {
      throw new IllegalStateException(path + " is not an array in " + root);
    }
    return (ArrayNode) node;
  }

  private static String expand(String template, Map<String, String> params) {
    return expand(template, params, TEMPLATE_PARAM);
  }

  private static String expand(String template, Map<String, String> params, Pattern param) {
    Matcher m = param.matcher(template);
    StringBuilder out = new StringBuilder();
    while (m.find()) {
      String value = params.get(m.group(1));
      if (value == null) {
        throw new IllegalStateException(
            "Path " + template + " names {" + m.group(1) + "}, which the state does not return");
      }
      String replacement =
          param == BODY_PARAM ? JSON.getNodeFactory().textNode(value).toString() : value;
      m.appendReplacement(out, Matcher.quoteReplacement(replacement));
    }
    m.appendTail(out);
    return out.toString();
  }

  /** The operationIds whose operation declares a request body, read off the served openapi. */
  private static Set<String> operationsTakingABody() throws IOException {
    JsonNode paths =
        JSON.readTree(
                given()
                    .when()
                    .get("/githost/q/openapi?format=json")
                    .then()
                    .statusCode(200)
                    .extract()
                    .asString())
            .path("paths");
    Set<String> ids = new TreeSet<>();
    paths.forEach(
        path ->
            path.forEach(
                operation -> {
                  if (operation.has("operationId") && operation.has("requestBody")) {
                    ids.add(operation.get("operationId").asText());
                  }
                }));
    return ids;
  }

  private static ArrayNode strings(List<String> values) {
    ArrayNode out = JsonNodeFactory.instance.arrayNode();
    values.forEach(out::add);
    return out;
  }

  /** Every committed {@code .json} under the directory, relative and {@code /}-separated. */
  private static List<String> committedJson(Path dir) throws IOException {
    if (!Files.isDirectory(dir)) {
      return List.of();
    }
    try (Stream<Path> files = Files.walk(dir)) {
      return files
          .filter(Files::isRegularFile)
          .filter(p -> p.getFileName().toString().endsWith(".json"))
          .map(p -> dir.relativize(p).toString().replace('\\', '/'))
          .sorted()
          .toList();
    }
  }
}
