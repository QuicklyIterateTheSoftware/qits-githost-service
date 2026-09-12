package eu.wohlben.qits.githost;

import static eu.wohlben.qits.githost.RefScopeHook.EXTERNAL_BRANCH_PATTERN;
import static eu.wohlben.qits.githost.RefScopeHook.matches;
import static eu.wohlben.qits.githost.RefScopeHook.rejectOutsideScope;
import static eu.wohlben.qits.githost.RefScopeHook.scopeOf;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.githost.RefScopeHook.Rule;
import eu.wohlben.qits.githost.RefScopeHook.Scope;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.security.runtime.QuarkusPrincipal;
import io.quarkus.security.runtime.QuarkusSecurityIdentity;
import io.vertx.core.json.JsonObject;
import jakarta.json.Json;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.transport.ReceiveCommand;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The four rules of C3 against the identity shapes the git host meets. Each rule runs with and
 * without {@code qits:admin} / {@code qits:system}, because roles must not widen a scope.
 *
 * <p>Before C3, {@code qits:git:external} together with {@code qits:admin} or {@code qits:system}
 * was not restricted at all. That pin now means the opposite: a workstation pattern restricts the
 * push whatever roles come with it ({@link #aWorkstationPatternRestrictsWhateverTheRoles}).
 */
class RefScopeHookTest {

  private static final String TICKET = "refs/heads/ticket/t-1";
  private static final String PERSON_REFUSAL =
      "refs/heads/main is outside the push scope: a person's credential may push only "
          + EXTERNAL_BRANCH_PATTERN;

  /** Role sets for rules 1 to 3: none, each privileged role, both, and both plus the external role. */
  static Stream<List<String>> roleSets() {
    return Stream.of(
        List.of(),
        List.of("qits:admin"),
        List.of("qits:system"),
        List.of("qits:admin", "qits:system"),
        List.of("qits:admin", "qits:system", "qits:git:external"));
  }

  /** Role sets that make a client token without a scope a platform service client (rule 4). */
  static Stream<List<String>> serviceClientRoleSets() {
    return Stream.of(
        List.of("qits:system"),
        List.of("qits:admin", "qits:system"),
        List.of("qits:system", "qits:agent"));
  }

  /** Role sets without qits:system: a client token without a scope that holds them pushes nothing. */
  static Stream<List<String>> unscopedClientRoleSets() {
    return Stream.of(
        List.of(),
        List.of("qits:admin"),
        List.of("qits:agent"),
        List.of("qits:ci-run"),
        List.of("qits-platform:system"));
  }

  // --- rule 1: git_refs -------------------------------------------------------------------------

  @ParameterizedTest
  @MethodSource("roleSets")
  void gitRefsDecideWhateverTheRoles(List<String> roles) {
    Scope scope = scopeOf(jwt(roles, Map.of("git_refs", List.of(TICKET))));

    assertEquals(Rule.GIT_REFS, scope.rule());
    assertFalse(scope.unrestricted());
    assertFalse(scope.tokenBypassAllowed());

    ReceiveCommand own = create(TICKET);
    assertFalse(rejectOutsideScope(List.of(own), scope));
    assertEquals(ReceiveCommand.Result.NOT_ATTEMPTED, own.getResult());

    ReceiveCommand main = create("refs/heads/main");
    assertTrue(rejectOutsideScope(List.of(main), scope));
    assertEquals(ReceiveCommand.Result.REJECTED_OTHER_REASON, main.getResult());
    assertEquals(
        "refs/heads/main is outside the push scope: this credential may push only " + TICKET,
        main.getMessage());
  }

  @Test
  void gitRefsAreReadAsQuarkusOidcHandsThemOver() {
    // A JSON array claim reaches the principal as a jakarta.json.JsonArray of JsonString.
    Scope scope =
        scopeOf(jwt(List.of(), Map.of("git_refs", Json.createArrayBuilder().add(TICKET).build())));

    assertEquals(Rule.GIT_REFS, scope.rule());
    assertTrue(scope.permits(TICKET));
    assertFalse(scope.permits("refs/heads/epic/e-1"));

    // The same through the suite's test mechanism, which builds claims the same way.
    Scope viaMechanism =
        scopeOf(
            TestTokenMechanism.identity(
                new JsonObject("{\"sub\":\"a\",\"git_refs\":[\"" + TICKET + "\"]}")));
    assertTrue(viaMechanism.permits(TICKET));
    assertFalse(viaMechanism.permits("refs/heads/main"));
  }

  @Test
  void anExactEntryMatchesOnlyItself() {
    assertTrue(matches(TICKET, TICKET));
    assertFalse(matches(TICKET, "refs/heads/ticket/t-10"));
    assertFalse(matches(TICKET, "refs/heads/ticket/t-1/part"));
    assertFalse(matches(TICKET, "refs/heads/ticket"));
  }

  @Test
  void anEntryEndingInSlashStarIsAPrefix() {
    String epic = "refs/heads/epic/e-1/*";
    assertTrue(matches(epic, "refs/heads/epic/e-1/task-2"));
    assertTrue(matches(epic, "refs/heads/epic/e-1/feature/f-3"));
    assertFalse(matches(epic, "refs/heads/epic/e-1"));
    assertFalse(matches(epic, "refs/heads/epic/e-10/task-2"));
    assertTrue(matches(EXTERNAL_BRANCH_PATTERN, "refs/heads/external/alice/topic"));
  }

  @Test
  void aMalformedEntryMatchesNothing() {
    assertFalse(matches("refs/*", "refs/heads/main"));
    assertFalse(matches("refs/tags/*", "refs/tags/v1"));
    assertFalse(matches("refs/heads/e*", "refs/heads/e1"));
    assertFalse(matches("refs/heads/*/x", "refs/heads/a/x"));
    assertFalse(matches("refs/heads/a*b", "refs/heads/a*b"));
    assertFalse(matches("heads/main", "heads/main"));
  }

  @Test
  void aTagIsNeverInsideAGitRefsScope() {
    Scope scope = scopeOf(jwt(List.of(), Map.of("git_refs", List.of("refs/heads/*"))));

    assertTrue(scope.permits("refs/heads/main"));
    assertFalse(scope.permits("refs/tags/v1"));
    assertFalse(scope.permits("refs/notes/review"));
  }

  @Test
  void anEmptyListPushesNothing() {
    Scope scope = scopeOf(jwt(List.of("qits:system"), Map.of("git_refs", List.of())));
    ReceiveCommand command = create("refs/heads/external/alice/topic");

    assertTrue(rejectOutsideScope(List.of(command), scope));
    assertEquals(
        "refs/heads/external/alice/topic is outside the push scope: this credential may push no"
            + " ref",
        command.getMessage());
  }

  @Test
  void anUnreadableListPushesNothing() {
    for (Object claim : List.of(TICKET, List.of(1), Map.of("ref", TICKET))) {
      Scope scope = scopeOf(jwt(List.of("qits:system"), Map.of("git_refs", claim)));
      ReceiveCommand command = create(TICKET);

      assertEquals(Rule.GIT_REFS, scope.rule());
      assertTrue(rejectOutsideScope(List.of(command), scope), String.valueOf(claim));
      assertEquals(RefScopeHook.UNREADABLE_GIT_REFS_REFUSAL, command.getMessage());
    }
  }

  @Test
  void gitRefsComeBeforeThePatternAndThePersonRule() {
    Scope scope =
        scopeOf(
            jwt(
                List.of("qits:git:external"),
                Map.of(
                    "git_refs", List.of(TICKET),
                    "git_ref_pattern", EXTERNAL_BRANCH_PATTERN,
                    "credential_type", "cli")));

    assertEquals(Rule.GIT_REFS, scope.rule());
    assertFalse(scope.permits("refs/heads/external/alice/topic"));
    assertTrue(scope.permits(TICKET));
  }

  // --- rule 2: git_ref_pattern ------------------------------------------------------------------

  @ParameterizedTest
  @MethodSource("roleSets")
  void aWorkstationPatternRestrictsWhateverTheRoles(List<String> roles) {
    // Changed on purpose: before C3 the external role plus qits:admin or qits:system was exempt.
    Scope scope =
        scopeOf(jwt(roles, Map.of("git_ref_pattern", EXTERNAL_BRANCH_PATTERN)));

    assertEquals(Rule.GIT_REF_PATTERN, scope.rule());
    assertFalse(scope.tokenBypassAllowed());
    assertFalse(rejectOutsideScope(List.of(create("refs/heads/external/alice/topic")), scope));

    ReceiveCommand main = create("refs/heads/main");
    assertTrue(rejectOutsideScope(List.of(main), scope));
    assertEquals(
        "refs/heads/main is outside the push scope: an external workstation credential may push"
            + " only " + EXTERNAL_BRANCH_PATTERN,
        main.getMessage());
  }

  @Test
  void aPatternOtherThanTheExternalOnePushesNothing() {
    Scope scope = scopeOf(jwt(List.of("qits:git:external"), Map.of("git_ref_pattern", "refs/heads/*")));
    ReceiveCommand command = create("refs/heads/external/alice/topic");

    assertTrue(rejectOutsideScope(List.of(command), scope));
    assertEquals(RefScopeHook.INVALID_PATTERN_REFUSAL, command.getMessage());
  }

  @Test
  void theExternalRoleWithoutAPatternPushesNothing() {
    // Before C3 this refused everything too. Without this rule such a token would fall to rule 4.
    Scope scope = scopeOf(jwt(List.of("qits:git:external"), Map.of()));
    ReceiveCommand command = create("refs/heads/external/alice/topic");

    assertEquals(Rule.GIT_REF_PATTERN, scope.rule());
    assertTrue(rejectOutsideScope(List.of(command), scope));
    assertEquals(RefScopeHook.INVALID_PATTERN_REFUSAL, command.getMessage());
  }

  @Test
  void theBootstrapIngressKeepsTheWorkstationCheck() {
    assertTrue(RefScopeHook.external(EXTERNAL_BRANCH_PATTERN).permits("refs/heads/external/seed"));
    assertFalse(RefScopeHook.external(EXTERNAL_BRANCH_PATTERN).permits("refs/heads/main"));
    assertFalse(RefScopeHook.external("refs/heads/disabled").permits("refs/heads/disabled"));
    assertFalse(RefScopeHook.external(null).permits("refs/heads/external/seed"));
  }

  // --- rule 3: a person -------------------------------------------------------------------------

  @ParameterizedTest
  @MethodSource("roleSets")
  void aCliTokenIsAPersonWhateverTheRoles(List<String> roles) {
    if (roles.contains("qits:git:external")) {
      return; // that role without a pattern is rule 2's; see theExternalRoleWithoutAPatternPushesNothing
    }
    Scope scope = scopeOf(jwt(roles, Map.of("credential_type", "cli")));

    assertEquals(Rule.PERSON, scope.rule());
    assertFalse(scope.tokenBypassAllowed());
    assertPersonScope(scope);
  }

  @ParameterizedTest
  @MethodSource("roleSets")
  void aForwardedIdentityIsAPersonWhateverTheRoles(List<String> roles) {
    Scope scope = scopeOf(forwarded(roles));

    assertEquals(Rule.PERSON, scope.rule());
    assertFalse(scope.tokenBypassAllowed());
    assertPersonScope(scope);
  }

  @Test
  void aPersonCannotPushMainTagsOrNotesOrDeleteMain() {
    Scope scope = scopeOf(forwarded(List.of("qits:admin")));
    for (ReceiveCommand command :
        List.of(
            create("refs/heads/main"),
            create("refs/tags/v1"),
            create("refs/notes/review"),
            delete("refs/heads/main"))) {
      assertTrue(rejectOutsideScope(List.of(command), scope));
      assertEquals(ReceiveCommand.Result.REJECTED_OTHER_REASON, command.getResult());
      assertTrue(
          command.getMessage().startsWith(command.getRefName() + " is outside the push scope"),
          command.getMessage());
    }
  }

  @Test
  void aPersonMayDeleteAnExternalBranch() {
    ReceiveCommand command = delete("refs/heads/external/alice/topic");

    assertFalse(rejectOutsideScope(List.of(command), scopeOf(forwarded(List.of()))));
    assertEquals(ReceiveCommand.Result.NOT_ATTEMPTED, command.getResult());
  }

  // --- rule 4: a client token without a scope ---------------------------------------------------

  @ParameterizedTest
  @MethodSource("unscopedClientRoleSets")
  void aClientTokenWithoutAScopeAndWithoutSystemPushesNothing(List<String> roles) {
    // A qits:agent or qits:ci-run commission that states no list must not fall through to
    // unrestricted: that would make losing the owner's roles a widening.
    Scope scope = scopeOf(jwt(roles, Map.of("project", "p-1", "context_kind", "agent-container")));
    ReceiveCommand command = create("refs/heads/ticket/t-1");

    assertEquals(Rule.UNSCOPED_CLIENT, scope.rule());
    assertFalse(scope.unrestricted());
    assertFalse(scope.tokenBypassAllowed());
    assertTrue(rejectOutsideScope(List.of(command), scope));
    assertEquals(RefScopeHook.UNSCOPED_CLIENT_REFUSAL, command.getMessage());
  }

  @Test
  void anAgentRoleWithAListPushesTheList() {
    Scope scope = scopeOf(jwt(List.of("qits:agent"), Map.of("git_refs", List.of(TICKET))));

    assertEquals(Rule.GIT_REFS, scope.rule());
    assertTrue(scope.permits(TICKET));
    assertFalse(scope.permits("refs/heads/main"));
  }

  @ParameterizedTest
  @MethodSource("serviceClientRoleSets")
  void aClientTokenWithoutAScopeIsNotRestricted(List<String> roles) {
    Scope scope = scopeOf(jwt(roles, Map.of("project", "*", "context_kind", "workspace")));

    assertEquals(Rule.SERVICE_CLIENT, scope.rule());
    assertTrue(scope.unrestricted());
    assertTrue(scope.tokenBypassAllowed());
    ReceiveCommand main = create("refs/heads/main");
    ReceiveCommand tag = create("refs/tags/v1");
    assertFalse(rejectOutsideScope(List.of(main, tag), scope));
    assertEquals(ReceiveCommand.Result.NOT_ATTEMPTED, main.getResult());
    assertEquals(ReceiveCommand.Result.NOT_ATTEMPTED, tag.getResult());
  }

  @Test
  void onlyRuleFourMayUseThePushToken() {
    assertTrue(scopeOf(jwt(List.of("qits:system"), Map.of())).tokenBypassAllowed());
    assertFalse(scopeOf(jwt(List.of("qits:system"), Map.of("git_refs", List.of("refs/heads/main"))))
        .tokenBypassAllowed());
    assertFalse(scopeOf(jwt(List.of("qits:system"), Map.of("git_ref_pattern", EXTERNAL_BRANCH_PATTERN)))
        .tokenBypassAllowed());
    assertFalse(scopeOf(jwt(List.of("qits:system"), Map.of("credential_type", "cli")))
        .tokenBypassAllowed());
    assertFalse(scopeOf(forwarded(List.of("qits:system"))).tokenBypassAllowed());
    assertFalse(scopeOf(jwt(List.of("qits:agent"), Map.of())).tokenBypassAllowed());
    assertFalse(scopeOf(null).tokenBypassAllowed());
  }

  // --- no identity ------------------------------------------------------------------------------

  @Test
  void aPushWithNoVerifiedIdentityPushesNothing() {
    SecurityIdentity anonymous = QuarkusSecurityIdentity.builder().setAnonymous(true).build();
    for (Scope scope : List.of(scopeOf(null), scopeOf(anonymous), RefScopeHook.nothing())) {
      ReceiveCommand command = create("refs/heads/external/alice/topic");

      assertEquals(Rule.NONE, scope.rule());
      assertTrue(rejectOutsideScope(List.of(command), scope));
      assertEquals(RefScopeHook.NO_IDENTITY_REFUSAL, command.getMessage());
    }
  }

  // --- the whole push ---------------------------------------------------------------------------

  @Test
  void aMixedPushIsRefusedWhole() {
    ReceiveCommand allowed = create("refs/heads/external/alice/topic");
    ReceiveCommand forbidden = create("refs/heads/main");

    assertTrue(
        rejectOutsideScope(List.of(allowed, forbidden), scopeOf(jwt(List.of(), Map.of("credential_type", "cli")))));

    assertEquals(ReceiveCommand.Result.REJECTED_OTHER_REASON, allowed.getResult());
    assertEquals(
        "refused with the whole push, because " + PERSON_REFUSAL, allowed.getMessage());
    assertEquals(ReceiveCommand.Result.REJECTED_OTHER_REASON, forbidden.getResult());
    assertEquals(PERSON_REFUSAL, forbidden.getMessage());
  }

  @Test
  void aLongScopeIsShortenedInTheRefusal() {
    List<String> refs =
        List.of(
            "refs/heads/epic/e-1",
            "refs/heads/task/1",
            "refs/heads/task/2",
            "refs/heads/task/3",
            "refs/heads/task/4",
            "refs/heads/task/5",
            "refs/heads/task/6");
    ReceiveCommand command = create("refs/heads/main");

    rejectOutsideScope(List.of(command), scopeOf(jwt(List.of(), Map.of("git_refs", refs))));

    assertEquals(
        "refs/heads/main is outside the push scope: this credential may push only"
            + " refs/heads/epic/e-1, refs/heads/task/1, refs/heads/task/2, refs/heads/task/3,"
            + " refs/heads/task/4 and 2 more",
        command.getMessage());
  }

  // --- helpers ----------------------------------------------------------------------------------

  private static void assertPersonScope(Scope scope) {
    assertFalse(rejectOutsideScope(List.of(create("refs/heads/external/alice/topic")), scope));
    ReceiveCommand main = create("refs/heads/main");
    assertTrue(rejectOutsideScope(List.of(main), scope));
    assertEquals(PERSON_REFUSAL, main.getMessage());
  }

  private static ReceiveCommand create(String ref) {
    return new ReceiveCommand(
        ObjectId.zeroId(), ObjectId.fromString("1111111111111111111111111111111111111111"), ref);
  }

  private static ReceiveCommand delete(String ref) {
    return new ReceiveCommand(
        ObjectId.fromString("1111111111111111111111111111111111111111"), ObjectId.zeroId(), ref);
  }

  /** A JWT identity, as quarkus-oidc makes one: the claims on the principal, groups as roles. */
  private static SecurityIdentity jwt(List<String> roles, Map<String, Object> claims) {
    QuarkusSecurityIdentity.Builder identity =
        QuarkusSecurityIdentity.builder().setPrincipal(new FakeJwt("some-client", claims));
    roles.forEach(identity::addRole);
    return identity.build();
  }

  /** An identity from the forwarded X-Qits-User / X-Qits-Roles headers: no JWT. */
  private static SecurityIdentity forwarded(List<String> roles) {
    QuarkusSecurityIdentity.Builder identity =
        QuarkusSecurityIdentity.builder().setPrincipal(new QuarkusPrincipal("alice"));
    roles.forEach(identity::addRole);
    return identity.build();
  }
}
