package eu.wohlben.qits.githost.api;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.EvaluationResult;
import eu.wohlben.qits.archrules.DeskAdminParityRules;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Parity between {@code qits:admin-agent} and {@code qits:desk-admin} (qits-1621) across this
 * service's doors.
 *
 * <p>The refusal list is empty: {@code CommitSubjectBypassResource.list} is the only route under
 * this package that admits {@code qits:admin-agent}, and it is a read (the repository's recorded
 * uses of the commit-subject break-glass), so {@code qits:desk-admin} is admitted beside it rather
 * than refused. {@code RepositoryRefsResource} admits neither {@code qits:admin-agent} nor {@code
 * qits:desk-admin} on any route — its machine primitives are {@code qits:system} and, for the one
 * read, {@code qits:agent} — so the parity rule has nothing to say about it either way; the push
 * refusal for {@code qits:desk-admin} is enforced one layer down, in {@code RefScopeHook}, which
 * this ArchUnit rule cannot see (see {@code RefScopeHookTest}).
 */
class DeskAdminParityTest {

  private static final Set<String> REFUSED = Set.of();

  private static final JavaClasses CLASSES =
      new ClassFileImporter().importPackages("eu.wohlben.qits.githost.api");

  @Test
  void everyAdminAgentRouteAdmitsDeskAdmin() {
    assertPasses(DeskAdminParityRules.everyAdminAgentRouteAdmitsDeskAdmin(REFUSED));
  }

  @Test
  void refusedRoutesExistAndRefuse() {
    assertPasses(DeskAdminParityRules.refusedRoutesExistAndRefuse(REFUSED));
  }

  private static void assertPasses(ArchRule rule) {
    EvaluationResult result = rule.evaluate(CLASSES);
    assertEquals("", String.join("\n", result.getFailureReport().getDetails()));
  }
}
