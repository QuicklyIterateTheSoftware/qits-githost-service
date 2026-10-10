package eu.wohlben.qits.githost;

import eu.wohlben.qits.githost.persistence.CommitSubjectBypassStore;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.eclipse.jgit.errors.MissingObjectException;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevObject;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.transport.PreReceiveHook;
import org.eclipse.jgit.transport.ReceiveCommand;
import org.eclipse.jgit.transport.ReceiveCommand.Result;
import org.eclipse.jgit.transport.ReceivePack;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Refuses a push that carries a new commit whose subject is not {@code term(<project>-<n>):
 * message}, on a repository that opts in. Syntax only: the id is never looked up, and nothing here
 * calls qits-projects.
 *
 * <h2>Opting in</h2>
 *
 * <p>A repository opts in with {@value #CONFIG_PATH} on its default branch (the tip of the branch
 * {@code HEAD} names, before this push lands) holding the line {@code enforce: true}. Anything else
 * — no file, no key, another value, an unreadable file, an unborn {@code HEAD} — is off, so the
 * guard ships inert on every repository and a repository turns it on with an ordinary commit. The
 * file is read with a line parser rather than a YAML library because none is on this service's
 * classpath and the whole format is one key.
 *
 * <h2>Which commits</h2>
 *
 * <p>Every commit reachable from a non-delete command's new id and from no ref the repository
 * already has — "new to the repository", branches and tags alike, an annotated tag peeled to its
 * commit. A merge commit's own subject is not checked (merge messages are machine-written), but
 * the commits it brings in are. The walk is capped at {@value #WALK_CAP} commits, and a push beyond
 * the cap is refused rather than waved through unchecked.
 *
 * <h2>Who is exempt</h2>
 *
 * <p>By identity, never by message shape: a platform service client ({@link
 * RefScopeHook.Rule#SERVICE_CLIENT}), the identity-less bootstrap ingress, and any pusher holding
 * one of {@code qits.githost.commit-subjects.exempt-roles} (default {@code qits:system,qits:ci-run},
 * which is what maintenance bumps run as). The first two stay exempt whatever that list says.
 *
 * <h2>The break-glass</h2>
 *
 * <p>{@code -o qits.subject-bypass=<reason>} lets a refused push through when the reason is not
 * blank. A use is recorded ({@link CommitSubjectBypassStore}) and logged at WARN only when the
 * guard would actually have refused; a failed record is logged at ERROR and the push still lands,
 * because a break-glass that needs a healthy database is not one.
 *
 * <p>Refusal is all-or-nothing, like {@link RefScopeHook}'s: every command is rejected, and the
 * explanation goes to the pusher as {@code remote:} lines.
 */
@ApplicationScoped
public class CommitSubjectHook {

  private static final Logger LOG = Logger.getLogger(CommitSubjectHook.class);

  /** The opt-in file, read from the default branch's tip. */
  static final String CONFIG_PATH = ".config/qits/commit-subjects.yml";

  /** {@code -o qits.subject-bypass=<reason>}. The bare option, with no {@code =}, is a blank reason. */
  static final String BYPASS_OPTION = "qits.subject-bypass";

  /** One qualified id, {@code <project>-<n>}: the reader's scope grammar. */
  private static final String ID = "[A-Za-z0-9][A-Za-z0-9-]*-[0-9]{1,18}";

  /**
   * The subject grammar, {@code term(<project>-<n>): message} or {@code term(<project>-<n>,
   * <project>-<n>, ...): message}. A strict subset of qits-projects' reader ({@code
   * CommitSubjectEntities}: head {@code ^([^()\s:]*)\(([^()]+)\)(!?):}, which then splits the head's
   * group 2 on {@code ,} and strips each part; scope {@code
   * ^([A-Za-z0-9][A-Za-z0-9-]*)-([0-9]{1,18})$}, which every stripped part must match), so every
   * subject accepted here names only entities the reader would also find.
   */
  static final Pattern SUBJECT =
      Pattern.compile("^[A-Za-z][A-Za-z0-9_/.-]*\\((" + ID + "(?:, *" + ID + ")*)\\)!?: \\S.*$");

  /** How many commits one push may bring before it is refused unchecked. */
  static final int WALK_CAP = 10_000;

  /** How many offending commits a refusal lists before it says "and N more". */
  static final int LISTED_COMMITS = 10;

  /** How much of the opt-in file is read; anything larger is not this format. */
  private static final int CONFIG_LIMIT = 64 * 1024;

  static final String REJECTION =
      "commit subject must be term(<project>-<n>): message, or"
          + " term(<project>-<n>, <project>-<n>): message";
  static final String CAP_REJECTION =
      "push brings more than " + WALK_CAP + " new commits; commit subjects not checked";
  static final String EXAMPLE = "feat(qits-1337): refuse malformed commit subjects";

  /**
   * Who is pushing, captured on the event loop with the push's scope.
   *
   * @param name the token's subject or client id; {@code "bootstrap-ingress"} for the ingress
   * @param roles the identity's roles
   * @param serviceClient whether {@link RefScopeHook} decided rule 4 with {@code qits:system}
   * @param bootstrap whether this is the identity-less bootstrap ingress
   */
  public record Pusher(String name, Set<String> roles, boolean serviceClient, boolean bootstrap) {

    public Pusher {
      roles = Set.copyOf(roles);
    }

    /** No identity captured: exempt from nothing, though the scope check refuses it first. */
    static Pusher nobody() {
      return new Pusher("(no identity)", Set.of(), false, false);
    }

    static Pusher bootstrapIngress() {
      return new Pusher("bootstrap-ingress", Set.of(), false, true);
    }
  }

  @ConfigProperty(
      name = "qits.githost.commit-subjects.exempt-roles",
      defaultValue = "qits:system,qits:ci-run")
  List<String> exemptRoles;

  @Inject CommitSubjectBypassStore bypasses;

  /** The hook to install, bound to the repo id the route resolved and the captured pusher. */
  public PreReceiveHook forRepository(String repoId, Pusher pusher) {
    return (rp, commands) -> onPreReceive(repoId, pusher, rp, commands);
  }

  /** Whether the grammar accepts this commit message's first line. */
  static boolean complies(String message) {
    return SUBJECT.matcher(firstLine(message)).matches();
  }

  static String firstLine(String message) {
    if (message == null) {
      return "";
    }
    int newline = message.indexOf('\n');
    return newline < 0 ? message : message.substring(0, newline);
  }

  boolean exempt(Pusher pusher) {
    if (pusher.serviceClient() || pusher.bootstrap()) {
      return true;
    }
    for (String role : exemptRoles == null ? List.<String>of() : exemptRoles) {
      String trimmed = role.trim();
      if (!trimmed.isEmpty() && pusher.roles().contains(trimmed)) {
        return true;
      }
    }
    return false;
  }

  void onPreReceive(
      String repoId, Pusher pusher, ReceivePack rp, Collection<ReceiveCommand> commands) {
    if (exempt(pusher)) {
      return;
    }
    Repository repo = rp.getRepository();
    if (!enforced(repoId, repo)) {
      return;
    }

    Verdict verdict;
    try {
      verdict = inspect(repo, commands);
    } catch (IOException | RuntimeException e) {
      // Receive-pack has already checked connectivity, so this is a storage fault, not a bad push.
      // The guard is a convention, not a lock; it must not strand the push that fixes the host.
      LOG.errorf(e, "could not walk the pushed commits of %s; commit subjects not checked", repoId);
      return;
    }
    if (verdict.clean()) {
      return;
    }

    List<String> options = rp.getPushOptions() == null ? List.of() : rp.getPushOptions();
    BypassOption bypass = bypassOption(options);
    if (bypass.reasonGiven()) {
      allowBypass(repoId, pusher, bypass.reason(), commands, verdict);
      return;
    }

    for (ReceiveCommand cmd : commands) {
      cmd.setResult(
          Result.REJECTED_OTHER_REASON, verdict.capExceeded() ? CAP_REJECTION : REJECTION);
    }
    LOG.infof(
        "refused push to %s by %s: %s",
        repoId,
        pusher.name(),
        verdict.capExceeded() ? "walk cap exceeded" : verdict.offenders().size() + " malformed");
    for (String line : explanation(verdict, bypass.presented())) {
      rp.sendMessage(line);
    }
  }

  // ---------------------------------------------------------------------------------------------
  // The opt-in file

  private boolean enforced(String repoId, Repository repo) {
    try {
      Ref head = repo.exactRef(Constants.HEAD);
      if (head == null || head.getObjectId() == null) {
        LOG.debugf("%s has an unborn HEAD; commit subjects not enforced", repoId);
        return false;
      }
      try (RevWalk walk = new RevWalk(repo)) {
        RevCommit tip = walk.parseCommit(head.getObjectId());
        try (TreeWalk tw = TreeWalk.forPath(repo, CONFIG_PATH, tip.getTree())) {
          if (tw == null) {
            return false;
          }
          ObjectLoader loader = repo.open(tw.getObjectId(0), Constants.OBJ_BLOB);
          if (loader.getSize() > CONFIG_LIMIT) {
            LOG.warnf("%s in %s is larger than %d bytes; not enforced", CONFIG_PATH, repoId,
                CONFIG_LIMIT);
            return false;
          }
          return enforceTrue(new String(loader.getCachedBytes(), StandardCharsets.UTF_8));
        }
      }
    } catch (IOException | RuntimeException e) {
      LOG.warnf(e, "could not read %s in %s; commit subjects not enforced", CONFIG_PATH, repoId);
      return false;
    }
  }

  /**
   * Whether the file holds a top-level {@code enforce: true}. A comment after the value is allowed;
   * the last occurrence of the key wins. Any other value is false.
   */
  static boolean enforceTrue(String yaml) {
    Boolean answer = null;
    for (String raw : yaml.split("\\R")) {
      if (raw.isEmpty() || Character.isWhitespace(raw.charAt(0))) {
        continue; // not a top-level key
      }
      String line = raw;
      int comment = line.indexOf(" #");
      if (comment >= 0) {
        line = line.substring(0, comment);
      }
      line = line.strip();
      int colon = line.indexOf(':');
      if (colon < 0 || !line.substring(0, colon).strip().equals("enforce")) {
        continue;
      }
      answer = line.substring(colon + 1).strip().equals("true");
    }
    return Boolean.TRUE.equals(answer);
  }

  // ---------------------------------------------------------------------------------------------
  // The walk

  record Offender(String sha, String subject) {}

  record Verdict(List<Offender> offenders, boolean capExceeded) {
    boolean clean() {
      return offenders.isEmpty() && !capExceeded;
    }
  }

  private static Verdict inspect(Repository repo, Collection<ReceiveCommand> commands)
      throws IOException {
    try (RevWalk walk = new RevWalk(repo)) {
      boolean started = false;
      for (ReceiveCommand cmd : commands) {
        if (cmd.getType() == ReceiveCommand.Type.DELETE
            || cmd.getResult() != Result.NOT_ATTEMPTED) {
          continue;
        }
        RevObject peeled = walk.peel(walk.parseAny(cmd.getNewId()));
        if (peeled instanceof RevCommit commit) {
          walk.markStart(commit);
          started = true;
        }
      }
      if (!started) {
        return new Verdict(List.of(), false);
      }
      for (Ref ref : repo.getRefDatabase().getRefs()) {
        ObjectId id = ref.getObjectId();
        if (id == null) {
          continue;
        }
        try {
          RevObject peeled = walk.peel(walk.parseAny(id));
          if (peeled instanceof RevCommit commit) {
            walk.markUninteresting(commit);
          }
        } catch (MissingObjectException gone) {
          // A ref naming an object this store lost cannot make anything old; skip it.
        }
      }
      List<Offender> offenders = new ArrayList<>();
      int seen = 0;
      for (RevCommit commit : walk) {
        if (++seen > WALK_CAP) {
          return new Verdict(offenders, true);
        }
        if (commit.getParentCount() > 1) {
          continue;
        }
        String message = commit.getFullMessage();
        if (!complies(message)) {
          offenders.add(new Offender(commit.name(), firstLine(message)));
        }
      }
      return new Verdict(offenders, false);
    }
  }

  // ---------------------------------------------------------------------------------------------
  // The break-glass

  record BypassOption(boolean presented, String reason) {
    boolean reasonGiven() {
      return reason != null && !reason.isBlank();
    }
  }

  static BypassOption bypassOption(List<String> options) {
    boolean presented = false;
    String reason = null;
    for (String option : options) {
      if (option.equals(BYPASS_OPTION)) {
        presented = true;
      } else if (option.startsWith(BYPASS_OPTION + "=")) {
        presented = true;
        String value = option.substring(BYPASS_OPTION.length() + 1).strip();
        if (!value.isEmpty()) {
          reason = value;
        }
      }
    }
    return new BypassOption(presented, reason);
  }

  private void allowBypass(
      String repoId,
      Pusher pusher,
      String reason,
      Collection<ReceiveCommand> commands,
      Verdict verdict) {
    List<String> refs = commands.stream().map(ReceiveCommand::getRefName).toList();
    List<String> shas = new ArrayList<>(verdict.offenders().stream().map(Offender::sha).toList());
    if (verdict.capExceeded()) {
      shas.add("(walk cap of " + WALK_CAP + " commits exceeded; not all commits checked)");
    }
    LOG.warnf(
        "commit-subject bypass used on %s by %s (%s): %s",
        repoId, pusher.name(), reason, String.join(", ", shas));
    try {
      bypasses.record(repoId, pusher.name(), reason, refs, shas);
    } catch (RuntimeException e) {
      LOG.errorf(
          e,
          "could not record the commit-subject bypass on %s by %s (%s); the push goes through",
          repoId,
          pusher.name(),
          reason);
    }
  }

  // ---------------------------------------------------------------------------------------------
  // The refusal

  static List<String> explanation(Verdict verdict, boolean blankBypassPresented) {
    List<String> lines = new ArrayList<>();
    lines.add("");
    if (verdict.capExceeded()) {
      lines.add(
          "qits-githost: this push brings more than "
              + WALK_CAP
              + " commits new to this repository, so their subjects were not checked and the push"
              + " is refused.");
      lines.add("Push in smaller pieces, or use the break-glass below.");
    } else {
      lines.add(
          "qits-githost: this repository requires every new commit's subject to name the work it"
              + " belongs to.");
      lines.add("These commits do not:");
      List<Offender> offenders = verdict.offenders();
      for (Offender offender : offenders.subList(0, Math.min(LISTED_COMMITS, offenders.size()))) {
        lines.add("  " + offender.sha().substring(0, 10) + "  " + offender.subject());
      }
      if (offenders.size() > LISTED_COMMITS) {
        lines.add("  ... and " + (offenders.size() - LISTED_COMMITS) + " more");
      }
    }
    lines.add("");
    lines.add(
        "Required form of the first line:  term(<project>-<n>): message, or"
            + " term(<project>-<n>, <project>-<n>): message");
    lines.add("For example:                      " + EXAMPLE);
    lines.add(
        "<project>-<n> is the qualified id of the ticket, epic or task the work belongs to, as"
            + " shown in its dispatch prompt and by `qits work`. Several ids may be given,"
            + " separated by a comma and an optional space, when a commit belongs to more than"
            + " one.");
    lines.add(
        "Merge commits are not checked, but the commits they bring in are. Rewrite a subject with"
            + " `git commit --amend` (the last commit) or `git rebase -i` (older ones), then push"
            + " again.");
    lines.add("");
    if (blankBypassPresented) {
      lines.add(
          "-o " + BYPASS_OPTION + " was given with an empty reason, which does not count.");
    }
    lines.add(
        "Break-glass: git push -o "
            + BYPASS_OPTION
            + "=\"<why>\" lets this push through; its use is recorded with your identity and"
            + " reason.");
    lines.add("(" + CONFIG_PATH + " with `enforce: true` on the default branch turns this on.)");
    lines.add("");
    return lines;
  }
}
