package eu.wohlben.qits.githost;

import io.quarkus.security.identity.SecurityIdentity;
import jakarta.json.JsonString;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.eclipse.jgit.transport.ReceiveCommand;
import org.eclipse.microprofile.jwt.JsonWebToken;

/**
 * Which refs a push may touch. This is the receive-pack half of principal-bound Git refs
 * ({@code principal-bound-git-refs-plan.md}, contract C3).
 *
 * <p>The decision is made once per push, at the HTTP boundary, from the verified identity ({@link
 * #scopeOf}). The result is an immutable {@link Scope}. The hook reads only that scope. It never
 * reads a header or a thread-local while JGit runs on a worker thread.
 *
 * <p>The rules, in this order:
 *
 * <ol>
 *   <li>The identity carries a {@value #GIT_REFS_CLAIM} claim: every ref the push touches must match
 *       an entry of that list, whatever roles the identity has. An entry is an exact ref, or a
 *       prefix pattern that ends in {@code /*}.
 *   <li>Else it carries a {@value #GIT_REF_PATTERN_CLAIM} claim (the workstation token): the pattern
 *       must be {@value #EXTERNAL_BRANCH_PATTERN} and every ref must be under it, whatever roles the
 *       identity has. A JWT with the {@value #EXTERNAL_GIT_ROLE} role and no such claim may push
 *       nothing, as before.
 *   <li>Else it is a person: a JWT with a {@value #CREDENTIAL_TYPE_CLAIM} claim (the {@code qits}
 *       CLI token), or an identity from the forwarded headers (no JWT). A person may push only
 *       {@value #EXTERNAL_BRANCH_PATTERN}, whatever roles the person has.
 *   <li>Else it is a client token that states no scope. If it holds {@value #SYSTEM_ROLE} — a static
 *       platform service, or a commission that still inherits its owner's roles — nothing here
 *       restricts it. Without {@value #SYSTEM_ROLE} (a {@code qits:agent} or {@code qits:ci-run}
 *       commission with no list) it may push nothing: a credential without a scope must not become
 *       unrestricted just because it lost the owner's roles.
 * </ol>
 *
 * <p>Only rule 4 with {@value #SYSTEM_ROLE} may use {@code -o qits.token=} ({@link
 * Scope#tokenBypassAllowed()}); see {@link ProtectedRefHook}. A push with no verified identity, or
 * with no scope captured at all, may push nothing.
 *
 * <p>Refusal is all-or-nothing, even when the Git client did not ask for the protocol's
 * {@code atomic} capability. Every command is rejected before JGit updates a ref, so a push with one
 * refused ref cannot land its other refs.
 */
final class RefScopeHook {

  static final String EXTERNAL_BRANCH_PREFIX = "refs/heads/external/";
  static final String EXTERNAL_BRANCH_PATTERN = EXTERNAL_BRANCH_PREFIX + "*";

  /** The list of refs a credential may push (contract C1). Absent means no scope is stated. */
  static final String GIT_REFS_CLAIM = "git_refs";

  /** The workstation token's single pattern. Older than {@link #GIT_REFS_CLAIM}. */
  static final String GIT_REF_PATTERN_CLAIM = "git_ref_pattern";

  /** Present on a token that a person holds (the {@code qits} CLI token says {@code cli}). */
  static final String CREDENTIAL_TYPE_CLAIM = "credential_type";

  static final String EXTERNAL_GIT_ROLE = "qits:git:external";

  /** What makes a client token without a scope a platform service client (rule 4). */
  static final String SYSTEM_ROLE = "qits:system";

  /** Every entry of a {@link #GIT_REFS_CLAIM} list starts with this. Anything else matches nothing. */
  private static final String BRANCH_PREFIX = "refs/heads/";

  /** How many scope entries a refusal lists before it says "and N more". */
  private static final int LISTED_ENTRIES = 5;

  static final String INVALID_PATTERN_REFUSAL =
      "an external workstation credential without a valid git_ref_pattern claim may push nothing";
  static final String UNREADABLE_GIT_REFS_REFUSAL =
      "this credential's git_refs claim is not a list of refs, so it may push nothing";
  static final String NO_IDENTITY_REFUSAL =
      "this push has no verified identity, so it may push nothing";
  static final String UNSCOPED_CLIENT_REFUSAL =
      "a client token with no git_refs claim and without qits:system may push nothing";

  /** Which rule decided a push's scope. */
  enum Rule {
    /** Rule 1: the {@link #GIT_REFS_CLAIM} list. */
    GIT_REFS,
    /** Rule 2: the workstation's {@link #GIT_REF_PATTERN_CLAIM}, or the bootstrap ingress. */
    GIT_REF_PATTERN,
    /** Rule 3: a person. */
    PERSON,
    /** Rule 4: a client token without a scope that holds {@link #SYSTEM_ROLE}. Unrestricted. */
    SERVICE_CLIENT,
    /** Rule 4 without {@link #SYSTEM_ROLE}: a client token that states no scope. Refuses everything. */
    UNSCOPED_CLIENT,
    /** No verified identity, or no captured scope. Refuses everything. */
    NONE
  }

  /**
   * One push's scope, captured on the event loop and read on the worker.
   *
   * @param rule the rule that decided it
   * @param holder who the refusal message says this is, such as {@code "a person's credential"}
   * @param refs the entries a ref must match; empty means no ref. Unused for {@link
   *     Rule#SERVICE_CLIENT}.
   * @param problem a refusal for every command whatever its ref, or {@code null}
   */
  record Scope(Rule rule, String holder, List<String> refs, String problem) {

    Scope {
      refs = List.copyOf(refs);
    }

    /** Whether nothing here restricts the push (rule 4). */
    boolean unrestricted() {
      return rule == Rule.SERVICE_CLIENT;
    }

    /** Whether {@code -o qits.token=} may bypass the default branch's protection (rule 4 only). */
    boolean tokenBypassAllowed() {
      return rule == Rule.SERVICE_CLIENT;
    }

    /** Whether this scope lets the push touch {@code ref}. */
    boolean permits(String ref) {
      if (unrestricted()) {
        return true;
      }
      if (problem != null) {
        return false;
      }
      return refs.stream().anyMatch(entry -> matches(entry, ref));
    }

    /** The allowed scope in words, such as "a person's credential may push only refs/heads/external/*". */
    String allowance() {
      if (refs.isEmpty()) {
        return holder + " may push no ref";
      }
      String listed = String.join(", ", refs.subList(0, Math.min(LISTED_ENTRIES, refs.size())));
      int more = refs.size() - LISTED_ENTRIES;
      return holder + " may push only " + listed + (more > 0 ? " and " + more + " more" : "");
    }
  }

  private RefScopeHook() {}

  /**
   * Rule 2's scope: the workstation pattern, which must be exactly {@value
   * #EXTERNAL_BRANCH_PATTERN}. The bootstrap ingress uses it too, with the pattern it was given.
   */
  static Scope external(String grantedPattern) {
    String holder = "an external workstation credential";
    return EXTERNAL_BRANCH_PATTERN.equals(grantedPattern)
        ? new Scope(Rule.GIT_REF_PATTERN, holder, List.of(EXTERNAL_BRANCH_PATTERN), null)
        : new Scope(Rule.GIT_REF_PATTERN, holder, List.of(), INVALID_PATTERN_REFUSAL);
  }

  /** The scope for a push whose scope was never captured, or that has no verified identity. */
  static Scope nothing() {
    return new Scope(Rule.NONE, "this push", List.of(), NO_IDENTITY_REFUSAL);
  }

  /**
   * Applies the four rules to the verified identity. Call it on the event loop, while the request
   * still carries its identity.
   */
  static Scope scopeOf(SecurityIdentity identity) {
    if (identity == null || identity.isAnonymous()) {
      return nothing();
    }
    if (!(identity.getPrincipal() instanceof JsonWebToken jwt)) {
      // An identity from the forwarded X-Qits-User / X-Qits-Roles headers: a browser session, which
      // is a person.
      return person();
    }
    if (jwt.containsClaim(GIT_REFS_CLAIM)) {
      return gitRefs(jwt.getClaim(GIT_REFS_CLAIM));
    }
    Optional<Object> pattern = jwt.claim(GIT_REF_PATTERN_CLAIM);
    if (pattern.isPresent() || identity.hasRole(EXTERNAL_GIT_ROLE)) {
      return external(pattern.map(RefScopeHook::text).orElse(null));
    }
    if (jwt.containsClaim(CREDENTIAL_TYPE_CLAIM)) {
      return person();
    }
    if (identity.hasRole(SYSTEM_ROLE)) {
      return new Scope(Rule.SERVICE_CLIENT, "a platform service client", List.of(), null);
    }
    return new Scope(
        Rule.UNSCOPED_CLIENT, "this credential", List.of(), UNSCOPED_CLIENT_REFUSAL);
  }

  private static Scope person() {
    return new Scope(
        Rule.PERSON, "a person's credential", List.of(EXTERNAL_BRANCH_PATTERN), null);
  }

  /**
   * Rule 1's scope. quarkus-oidc hands a JSON array claim over as a {@code jakarta.json.JsonArray}
   * of {@link JsonString}; plain strings are read as well. Anything else is not a list of refs, and
   * a credential whose list cannot be read may push nothing.
   */
  private static Scope gitRefs(Object claim) {
    String holder = "this credential";
    if (!(claim instanceof Collection<?> entries)) {
      return new Scope(Rule.GIT_REFS, holder, List.of(), UNREADABLE_GIT_REFS_REFUSAL);
    }
    List<String> refs = new ArrayList<>();
    for (Object entry : entries) {
      String ref = text(entry);
      if (ref == null) {
        return new Scope(Rule.GIT_REFS, holder, List.of(), UNREADABLE_GIT_REFS_REFUSAL);
      }
      refs.add(ref);
    }
    return new Scope(Rule.GIT_REFS, holder, refs, null);
  }

  /** A claim value as a string: a plain string or a JSON string. {@code null} for anything else. */
  private static String text(Object value) {
    if (value instanceof String string) {
      return string;
    }
    if (value instanceof JsonString json) {
      return json.getString();
    }
    return null;
  }

  /**
   * Whether one scope entry covers {@code ref}. An entry that ends in {@code /*} is a prefix; any
   * other entry must equal the ref. An entry outside {@code refs/heads/}, or with a {@code *}
   * anywhere else, matches nothing: the idp refuses such entries, and this host does not guess.
   */
  static boolean matches(String entry, String ref) {
    if (entry == null || ref == null || !entry.startsWith(BRANCH_PREFIX)) {
      return false;
    }
    int star = entry.indexOf('*');
    if (star < 0) {
      return entry.equals(ref);
    }
    if (star != entry.length() - 1 || !entry.endsWith("/*")) {
      return false;
    }
    return ref.startsWith(entry.substring(0, star));
  }

  /**
   * Rejects every command when the scope refuses any of them.
   *
   * @return {@code true} when every command was rejected, so the caller must not run further hooks
   */
  static boolean rejectOutsideScope(Collection<ReceiveCommand> commands, Scope scope) {
    if (scope.unrestricted()) {
      return false;
    }
    if (scope.problem() != null) {
      for (ReceiveCommand command : commands) {
        command.setResult(ReceiveCommand.Result.REJECTED_OTHER_REASON, scope.problem());
      }
      return true;
    }
    String firstRefused =
        commands.stream()
            .map(ReceiveCommand::getRefName)
            .filter(ref -> !scope.permits(ref))
            .findFirst()
            .orElse(null);
    if (firstRefused == null) {
      return false;
    }
    String allowance = scope.allowance();
    for (ReceiveCommand command : commands) {
      String ref = command.getRefName();
      command.setResult(
          ReceiveCommand.Result.REJECTED_OTHER_REASON,
          scope.permits(ref)
              ? "refused with the whole push, because " + firstRefused + " is outside the push"
                  + " scope: " + allowance
              : ref + " is outside the push scope: " + allowance);
    }
    return true;
  }
}
