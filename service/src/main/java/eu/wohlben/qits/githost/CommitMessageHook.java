package eu.wohlben.qits.githost;

import jakarta.enterprise.context.ApplicationScoped;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.eclipse.jgit.errors.MissingObjectException;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevObject;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.transport.PreReceiveHook;
import org.eclipse.jgit.transport.ReceiveCommand;
import org.eclipse.jgit.transport.ReceiveCommand.Result;
import org.eclipse.jgit.transport.ReceivePack;
import org.jboss.logging.Logger;

/**
 * Refuses a push that carries a new commit whose message matches none of {@link
 * CommitMessageVariants} (qits-706). Every repository, every pusher, every receive route — and
 * nothing lets such a commit through: there is no opt-in file, no exemption by identity and no
 * push option. Syntax only: an id is never looked up, and nothing here calls qits-projects.
 *
 * <h2>Which commits</h2>
 *
 * <p>Every commit reachable from a non-delete command's new id and from no ref the repository
 * already has — "new to the repository", branches and tags alike, an annotated tag peeled to its
 * commit. A merge commit is accepted as {@link CommitMessageVariants#MERGE} whatever its message,
 * but the commits it brings in are each checked. The walk is capped at {@value #WALK_CAP} commits,
 * and a push beyond the cap is refused rather than waved through unchecked.
 *
 * <h2>Adopting history</h2>
 *
 * <p>A push into a repository that has <b>no ref at all</b> before it lands is not checked: an
 * import, a cold bootstrap, the {@code /bootstrap-git} ingress. History written elsewhere is
 * adopted as it is, because there is nothing to rewrite it onto. "No ref" means no ref that names
 * an object — a freshly provisioned repository's {@code HEAD} is a symbolic ref to an unborn
 * branch, and it does not count. Once any ref exists, every new commit is checked.
 *
 * <p>Refusal is all-or-nothing, like {@link RefScopeHook}'s: every command is rejected, and the
 * explanation goes to the pusher as {@code remote:} lines.
 */
@ApplicationScoped
public class CommitMessageHook {

  private static final Logger LOG = Logger.getLogger(CommitMessageHook.class);

  /** How many commits one push may bring before it is refused unchecked. */
  static final int WALK_CAP = 10_000;

  /** How many offending commits a refusal lists before it says "and N more". */
  static final int LISTED_COMMITS = 10;

  static final String REJECTION =
      "commit message matches no accepted form; see the remote: lines for the forms";
  static final String CAP_REJECTION =
      "push brings more than " + WALK_CAP + " new commits; commit messages not checked";

  /** The hook to install, bound to the repo id the route resolved (for its log lines). */
  public PreReceiveHook forRepository(String repoId) {
    return (rp, commands) -> onPreReceive(repoId, rp, commands);
  }

  /** Whether some variant accepts this commit. */
  static boolean complies(String message, int parents) {
    return CommitMessageVariants.classify(message, parents).isPresent();
  }

  void onPreReceive(String repoId, ReceivePack rp, Collection<ReceiveCommand> commands) {
    Repository repo = rp.getRepository();
    Verdict verdict;
    try {
      if (!hasRefs(repo)) {
        LOG.debugf("%s has no ref yet; its first push adopts its history unchecked", repoId);
        return;
      }
      verdict = inspect(repo, commands);
    } catch (IOException | RuntimeException e) {
      // Receive-pack has already checked connectivity, so this is a storage fault, not a bad push.
      // The guard is a convention, not a lock; it must not strand the push that fixes the host.
      LOG.errorf(e, "could not walk the pushed commits of %s; commit messages not checked", repoId);
      return;
    }
    if (verdict.clean()) {
      return;
    }

    for (ReceiveCommand cmd : commands) {
      cmd.setResult(
          Result.REJECTED_OTHER_REASON, verdict.capExceeded() ? CAP_REJECTION : REJECTION);
    }
    LOG.infof(
        "refused push to %s: %s",
        repoId,
        verdict.capExceeded() ? "walk cap exceeded" : verdict.offenders().size() + " malformed");
    for (String line : explanation(verdict)) {
      rp.sendMessage(line);
    }
  }

  /**
   * Whether the repository has any ref that names an object, as it stands before this push lands.
   * A symbolic ref to an unborn branch — the {@code HEAD} every provisioned repository starts with
   * — resolves to no object and does not count.
   */
  static boolean hasRefs(Repository repo) throws IOException {
    for (Ref ref : repo.getRefDatabase().getRefs()) {
      if (ref.getObjectId() != null) {
        return true;
      }
    }
    return false;
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
        String message = commit.getFullMessage();
        if (!complies(message, commit.getParentCount())) {
          offenders.add(new Offender(commit.name(), CommitMessageVariants.firstLine(message)));
        }
      }
      return new Verdict(offenders, false);
    }
  }

  // ---------------------------------------------------------------------------------------------
  // The refusal

  static List<String> explanation(Verdict verdict) {
    List<String> lines = new ArrayList<>();
    lines.add("");
    if (verdict.capExceeded()) {
      lines.add(
          "qits-githost: this push brings more than "
              + WALK_CAP
              + " commits new to this repository, so their messages were not checked and the push"
              + " is refused. Push in smaller pieces.");
    } else {
      lines.add(
          "qits-githost: every commit this host accepts must have a message in one of the forms"
              + " below. These commits do not:");
      List<Offender> offenders = verdict.offenders();
      for (Offender offender : offenders.subList(0, Math.min(LISTED_COMMITS, offenders.size()))) {
        lines.add("  " + offender.sha().substring(0, 10) + "  " + offender.subject());
      }
      if (offenders.size() > LISTED_COMMITS) {
        lines.add("  ... and " + (offenders.size() - LISTED_COMMITS) + " more");
      }
    }
    lines.add("");
    lines.addAll(CommitMessageVariants.acceptedForms());
    lines.add(
        "A merge's own message is accepted, but the commits it brings in are checked. Rewrite a"
            + " message with `git commit --amend` (the last commit) or `git rebase -i` (older"
            + " ones), then push again.");
    lines.add("");
    return lines;
  }
}
