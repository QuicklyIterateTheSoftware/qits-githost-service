package eu.wohlben.qits.githost.loc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

public class LanguageTest {

  @Test
  public void namesALanguageByExtensionAnywhereInTheTree() {
    assertEquals(Optional.of("Java"), Language.of("src/main/java/App.java"));
    assertEquals(Optional.of("TypeScript"), Language.of("web/thing.tsx"));
    assertEquals(Optional.of("YAML"), Language.of("deploy/values.yml"));
    assertEquals(Optional.of("Markdown"), Language.of("README.md"));
  }

  @Test
  public void theExtensionIsCaseInsensitive() {
    assertEquals(Optional.of("Java"), Language.of("App.JAVA"));
    assertEquals(Optional.of("SQL"), Language.of("db/V1__init.SQL"));
  }

  @Test
  public void aDockerfileIsNamedByItsBasenameWithOrWithoutAVariantSuffix() {
    assertEquals(Optional.of("Dockerfile"), Language.of("docker/Dockerfile"));
    assertEquals(Optional.of("Dockerfile"), Language.of("docker/Dockerfile.builder"));
    assertEquals(Optional.of("Dockerfile"), Language.of("Containerfile"));
    assertEquals(Optional.of("Makefile"), Language.of("Makefile"));
  }

  @Test
  public void anUnknownExtensionADotfileAndABareNameAnswerEmpty() {
    assertTrue(Language.of("notes.txt").isEmpty());
    assertTrue(Language.of("src/app/main.lock").isEmpty());
    assertTrue(Language.of(".gitignore").isEmpty());
    assertTrue(Language.of("LICENSE").isEmpty());
  }

  @Test
  public void lockfilesAreNotNamed() {
    for (String lockfile :
        List.of("package-lock.json", "web/pnpm-lock.yaml", "npm-shrinkwrap.json", "yarn.lock")) {
      assertEquals(Optional.empty(), Language.of(lockfile), lockfile);
    }
    assertEquals(Optional.of("JSON"), Language.of("package.json"));
  }

  @Test
  public void everyNamedLanguageHasACategory() {
    for (String language : Language.names()) {
      Language.categoryOf(language); // throws for a language missing from the category map
    }
    assertEquals(Language.Category.DATA, Language.categoryOf("JSON"));
    assertEquals(Language.Category.DOCS, Language.categoryOf("Markdown"));
    assertEquals(Language.Category.CODE, Language.categoryOf("Java"));
  }
}
