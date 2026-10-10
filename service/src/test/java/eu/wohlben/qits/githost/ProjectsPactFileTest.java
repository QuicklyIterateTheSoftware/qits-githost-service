package eu.wohlben.qits.githost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import au.com.dius.pact.core.model.DefaultPactWriter;
import au.com.dius.pact.core.model.PactSpecVersion;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import eu.wohlben.qits.githost.contracts.GoldenFiles;
import eu.wohlben.qits.githost.contracts.ProviderGoldenMasters;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * <b>The committed consumer pact, {@code pacts/qits-githost-service_qits-projects-service.json}</b>
 * (ticket qits-1149), modeled on qits-maintenance-service's class of the same name.
 *
 * <p>Compares by default: pact-jvm writes what {@link ProjectsContract} describes (raw, to {@code
 * service/target/pacts/}); the test normalises it — interactions sorted, pact-jvm's own version
 * stripped, 2-space indentation, a trailing newline — and compares it byte for byte with the
 * committed file. {@code -Dgolden.update=true} (or {@code QITS_GOLDEN_UPDATE=true}) rewrites it.
 * The platform packs {@code pacts/*_qits-projects-service.json} into this repository's pacts jar
 * for qits-projects (see {@code .config/qits/release.yml}).
 */
class ProjectsPactFileTest {

  static final String FILE = "qits-githost-service_qits-projects-service.json";

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Test
  void theCommittedPactIsWhatTheContractWrites() throws IOException {
    String raw = written();
    Path scratch = Path.of("target", "pacts", FILE);
    Files.createDirectories(scratch.getParent());
    Files.writeString(scratch, raw);

    GoldenFiles.compareOrWrite(
        GoldenFiles.repositoryRoot().resolve("pacts").resolve(FILE), normalise(raw));
  }

  /** Both references on every interaction, and status-only rows carry no body. */
  @Test
  void everyInteractionCarriesBothReferences() throws IOException {
    JsonNode pact = MAPPER.readTree(normalise(written()));
    assertEquals(ProviderGoldenMasters.CONSUMER, pact.path("consumer").path("name").asText());
    assertEquals("qits-projects-service", pact.path("provider").path("name").asText());
    assertEquals("4.0", pact.path("metadata").path("pactSpecification").path("version").asText());

    JsonNode interactions = pact.path("interactions");
    assertEquals(ProjectsContract.CASES.size(), interactions.size(), "one interaction per row");
    Set<String> unique = new HashSet<>();
    for (JsonNode interaction : interactions) {
      String description = interaction.path("description").asText();
      JsonNode references = interaction.path("comments").path("references");

      JsonNode call = references.path("qits-call");
      assertStrings(description, call, "app", "operationId");
      assertEquals("qits-projects-service", call.path("app").asText(), description);

      JsonNode trigger = references.path("qits-trigger");
      assertStrings(description, trigger, "kind", "app", "operationId");
      assertEquals(ProviderGoldenMasters.CONSUMER, trigger.path("app").asText(), description);
      assertTrue(
          description.startsWith(trigger.path("operationId").asText() + ": "),
          description + ": the description leads with the trigger");

      String state = interaction.path("providerStates").path(0).path("name").asText();
      assertTrue(unique.add(description + "\u0000" + state), "(description, state) repeats: " + description);
    }
  }

  private static void assertStrings(String description, JsonNode group, String... keys) {
    assertTrue(group.isObject(), description + ": reference group missing");
    assertEquals(keys.length, group.size(), description + ": " + group + " holds other keys");
    for (String key : keys) {
      JsonNode value = group.path(key);
      assertTrue(
          value.isTextual() && !value.asText().isBlank(),
          description + ": " + key + " must be a non-blank string, got " + value);
    }
  }

  /** The pact as pact-jvm's own writer serialises it. */
  static String written() {
    StringWriter out = new StringWriter();
    try (PrintWriter writer = new PrintWriter(out)) {
      DefaultPactWriter.INSTANCE.writePact(ProjectsContract.pact(), writer, PactSpecVersion.V4);
    }
    return out.toString();
  }

  /** Sorted, version-stripped, 2-space indented, one trailing newline. */
  static String normalise(String raw) throws IOException {
    ObjectNode pact = (ObjectNode) MAPPER.readTree(raw);
    if (pact.path("metadata") instanceof ObjectNode meta) {
      meta.remove("pact-jvm");
    }
    if (pact.path("interactions") instanceof ArrayNode interactions) {
      List<JsonNode> sorted = new ArrayList<>();
      interactions.forEach(sorted::add);
      sorted.sort(
          Comparator.comparing((JsonNode i) -> i.path("description").asText())
              .thenComparing(i -> i.path("providerStates").path(0).path("name").asText()));
      interactions.removeAll();
      sorted.forEach(interactions::add);
    }
    StringBuilder out = new StringBuilder();
    print(pact, "", out);
    return out.append('\n').toString();
  }

  /** {@code JSON.stringify(value, null, 2)}: no space before a colon, empty containers as {} / []. */
  private static void print(JsonNode node, String indent, StringBuilder out) throws IOException {
    String inner = indent + "  ";
    if (node.isObject()) {
      if (node.isEmpty()) {
        out.append("{}");
        return;
      }
      out.append("{\n");
      Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
      while (fields.hasNext()) {
        Map.Entry<String, JsonNode> field = fields.next();
        out.append(inner).append(MAPPER.writeValueAsString(field.getKey())).append(": ");
        print(field.getValue(), inner, out);
        out.append(fields.hasNext() ? ",\n" : "\n");
      }
      out.append(indent).append('}');
    } else if (node.isArray()) {
      if (node.isEmpty()) {
        out.append("[]");
        return;
      }
      out.append("[\n");
      for (int i = 0; i < node.size(); i++) {
        out.append(inner);
        print(node.get(i), inner, out);
        out.append(i < node.size() - 1 ? ",\n" : "\n");
      }
      out.append(indent).append(']');
    } else {
      out.append(MAPPER.writeValueAsString(node));
    }
  }
}
