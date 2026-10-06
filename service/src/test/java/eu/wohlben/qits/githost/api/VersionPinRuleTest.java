package eu.wohlben.qits.githost.api;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

import eu.wohlben.qits.githost.api.PinFormat.Token;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.eclipse.jgit.diff.RawText;
import org.eclipse.jgit.diff.RawTextComparator;
import org.eclipse.jgit.merge.MergeAlgorithm;
import org.eclipse.jgit.merge.MergeResult;
import org.junit.jupiter.api.Test;

/**
 * The version-pin engine and its formats, without a repository: a {@link MergeResult} is what the
 * merger hands the engine, and {@link MergeAlgorithm} is the same code that makes it in a fold.
 *
 * <p>The point of this class is the extension point. The merge-door tests in {@code
 * RepositoryMergeResourceTest} prove the shipped formats through the wire; here a format that
 * exists only in this test is handed to the engine and decides a file no shipped format reads —
 * which is the whole claim of {@link PinFormat}: an ecosystem is one class and one list entry.
 */
public class VersionPinRuleTest {

  /**
   * A test-only ecosystem: files called {@code VERSIONS} holding {@code name = v1.2.3} lines, where
   * the {@code v} belongs to the tokenizer — the shape a Go format would have.
   */
  static final class VersionsFile implements PinFormat {
    @Override
    public String name() {
      return "versions-file";
    }

    @Override
    public boolean appliesTo(String path) {
      return "VERSIONS".equals(PinVersions.basename(path));
    }

    @Override
    public List<Token> tokens(String line) {
      int eq = line.indexOf("= v");
      if (eq < 0) {
        return List.of();
      }
      int start = eq + 2;
      String text = line.substring(start).strip();
      return text.matches("v\\d+(\\.\\d+)+")
          ? List.of(new Token(start, start + text.length(), text, text.substring(1), null))
          : List.of();
    }
  }

  private static MergeResult<RawText> merge(String base, String ours, String theirs) {
    return new MergeAlgorithm()
        .merge(RawTextComparator.DEFAULT, text(base), text(ours), text(theirs));
  }

  private static RawText text(String content) {
    return new RawText(content.getBytes(StandardCharsets.UTF_8));
  }

  @Test
  public void aFormatPluggedIntoTheRegistryIsPickedUpByPath() throws Exception {
    MergeResult<RawText> result =
        merge("x = v1.2.0\nkeep\n", "x = v1.10.0\nkeep\n", "x = v1.9.3\nkeep\n");

    // Not a file any shipped format reads, so the registered set leaves it conflicted...
    assertThat(VersionPinRule.decide("tools/VERSIONS", result), is(nullValue()));

    // ...and the same engine decides it the moment the format is in the list.
    List<PinFormat> registry = new ArrayList<>(PinFormats.REGISTERED);
    registry.add(new VersionsFile());
    assertThat(PinFormats.forPath("tools/VERSIONS", registry).orElseThrow(), instanceOf(VersionsFile.class));
    VersionPinRule.Decision decision = VersionPinRule.decide("tools/VERSIONS", result, registry);
    assertThat(decision, is(notNullValue()));
    assertThat(
        new String(decision.content(), StandardCharsets.UTF_8), is("x = v1.10.0\nkeep\n"));
    assertThat(decision.versions().get(0).chosen(), is("v1.10.0"));
    assertThat(decision.versions().get(0).line(), is(1));

    // The engine's structural rules are not the format's to loosen: a changed neighbour in the same
    // hunk is still a conflict whatever the format says.
    assertThat(
        VersionPinRule.decide(
            "tools/VERSIONS",
            merge("x = v1.2.0\nkeep\n", "x = v1.3.0\nkept\n", "x = v1.4.0\nkeep\n"),
            registry),
        is(nullValue()));
  }

  @Test
  public void aPathTwoFormatsClaimIsDecidedByNeither() {
    List<PinFormat> registry = List.of(new VersionsFile(), new VersionsFile());
    assertThat(PinFormats.forPath("VERSIONS", registry).isPresent(), is(false));
  }

  @Test
  public void theRegistryMatchesManifestsByBasenameAndNeverALockfile() {
    assertThat(
        PinFormats.forPath("a/b/pom.xml", PinFormats.REGISTERED).orElseThrow(),
        instanceOf(MavenPom.class));
    assertThat(
        PinFormats.forPath("package.json", PinFormats.REGISTERED).orElseThrow(),
        instanceOf(NpmPackageJson.class));
    for (String docker : List.of("Dockerfile", "x/runtime.Dockerfile", "Dockerfile.dev")) {
      assertThat(
          PinFormats.forPath(docker, PinFormats.REGISTERED).orElseThrow(),
          instanceOf(Dockerfile.class));
    }
    for (String other :
        List.of("package-lock.json", "pom.xml.orig", "README.md", ".Dockerfile", "Dockerfile.")) {
      assertThat(other, PinFormats.forPath(other, PinFormats.REGISTERED).isPresent(), is(false));
    }
  }

  @Test
  public void aDockerfileReadsOnlyFromTagsAndTrivialArgs() {
    Dockerfile docker = new Dockerfile();
    assertThat(
        texts(docker.tokens("FROM --platform=$BUILDPLATFORM host:5000/a/b:1.2.3-alpine AS build")),
        contains("1.2.3-alpine"));
    assertThat(docker.tokens("FROM eclipse-temurin:21-jdk").get(0).value(), is("21"));
    assertThat(docker.tokens("FROM eclipse-temurin:21-jdk").get(0).qualifier(), is("jdk"));
    assertThat(texts(docker.tokens("from node:20.11.1\r")), contains("20.11.1"));
    assertThat(texts(docker.tokens("ARG NODE_VERSION=20.11.1")), contains("20.11.1"));
    // Not versions: a digest, a word tag, a variable, an untrivial ARG, anything that is not FROM.
    assertThat(docker.tokens("FROM alpine:3.19@sha256:" + "a".repeat(64)), is(empty()));
    assertThat(docker.tokens("FROM alpine@sha256:" + "a".repeat(64)), is(empty()));
    assertThat(docker.tokens("FROM debian:bookworm"), is(empty()));
    assertThat(docker.tokens("FROM node:${NODE_VERSION}"), is(empty()));
    assertThat(docker.tokens("ARG NODE_VERSION=20.11.1 # pinned"), is(empty()));
    assertThat(docker.tokens("ENV TOOL_VERSION=1.2.3"), is(empty()));
    assertThat(docker.tokens("RUN curl -L https://x/tool-1.2.3/tool"), is(empty()));
  }

  @Test
  public void theSharedOrderingIsNumericAndRefusesWhatHasNoNewerSide() {
    assertThat(PinVersions.compareNumbers("2026.1006.5", "2026.919.120127") > 0, is(true));
    assertThat(PinVersions.compareNumbers("1.4.10", "1.4.2") > 0, is(true));
    assertThat(PinVersions.order(token("1.2", null), token("1.2.0", null)).isPresent(), is(false));
    assertThat(PinVersions.order(token("01", null), token("1", null)).isPresent(), is(false));
    assertThat(
        PinVersions.order(token("1.3.0", "SNAPSHOT"), token("1.3.0", null)).isPresent(), is(false));
    assertThat(PinVersions.order(token("1.3", "jre"), token("1.4", "jre")).getAsInt() < 0, is(true));
  }

  private static Token token(String value, String qualifier) {
    String text = qualifier == null ? value : value + "-" + qualifier;
    return new Token(0, text.length(), text, value, qualifier);
  }

  private static List<String> texts(List<Token> tokens) {
    return tokens.stream().map(Token::text).toList();
  }
}
