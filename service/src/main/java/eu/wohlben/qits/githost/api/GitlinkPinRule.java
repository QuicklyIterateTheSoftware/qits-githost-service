package eu.wohlben.qits.githost.api;

import eu.wohlben.qits.githost.GitRepositoryProvider;
import eu.wohlben.qits.githost.RepositoryNameResolver;
import eu.wohlben.qits.githost.api.RepositoryRefsResource.ResolvedGitlink;
import jakarta.enterprise.inject.Instance;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import org.eclipse.jgit.errors.ConfigInvalidException;
import org.eclipse.jgit.errors.MissingObjectException;
import org.eclipse.jgit.lib.Config;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevObject;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.jboss.logging.Logger;

/**
 * The gitlink-pin merge rule: a superproject's submodule conflict where both heads pin a
 * <b>release</b> of the same sibling, and the newer release contains the older one, is decided for
 * the newer release.
 *
 * <h2>Why it lives here</h2>
 *
 * <p>Git cannot merge a gitlink — it is forty hex characters in a tree entry, two sides wrote two
 * different ones, and there is no text to merge — so a wrapper fold that is otherwise perfectly
 * foldable used to come back as a 409, and qits-projects' {@code ConflictResolver} would read the
 * declarations, look the shas up as releases, ask this host whether one contains the other, and fold
 * a second time with {@code resolutions}. Every fact that decision needs is held <b>here</b>: the
 * wrapper's {@code .gitmodules}, the sibling's tags and the sibling's history. The rule moved to
 * where its facts are, so a fold is decided in one round trip and against the objects themselves
 * rather than a table that mirrors them.
 *
 * <h2>A merge rule, not a write door</h2>
 *
 * <p>Like {@link VersionPinRule}, nothing a caller sends reaches the tree: the caller only switches
 * the rule on ({@code MergeRequest#gitlinkPins}) and names its project; the pin written is always
 * one of the two the heads already held. An explicit {@code Resolution} for the same path wins — a
 * caller that said what it wants is not second-guessed by a rule.
 *
 * <h2>Narrow on purpose</h2>
 *
 * <p>A path is decided only when every one of these holds, and when in doubt it is a conflict:
 *
 * <ol>
 *   <li>it is an unmerged gitlink conflict with both sides present and different — a side without
 *       the submodule is a decision about the estate's membership, and that is a person's;
 *   <li>the {@code .gitmodules} of the head being folded in at this step declares a {@code
 *       submodule.<name>} whose {@code path} is the conflicting path — that commit is the one whose
 *       declaration the conflict is about, and the one the 409 reports as {@code headSha};
 *   <li>{@code (projectId, <name>)} resolves to a repository this host holds, through the same
 *       {@link RepositoryNameResolver} the name-addressed git routes use;
 *   <li>both shas are the peeled commit of a calendar-version tag {@code refs/tags/YYYY.MDD.HMMSS}
 *       in that sibling (the highest one, if a commit carries several);
 *   <li>the two versions differ, compared numerically segment by segment ({@link #compare});
 *   <li>the release with the higher version <b>strictly contains</b> the other in the sibling's
 *       history.
 * </ol>
 *
 * <p><b>The sixth is not decoration.</b> A release cut from a side branch has a newer version while
 * containing none of what the older one shipped; pinning it by version alone would silently drop
 * released work out of the estate. And a descendant carrying the LOWER version means ancestry and
 * version disagree about which is later — that is a broken release line, not a decision.
 *
 * <h2>Posture</h2>
 *
 * <p><b>Nothing here fails the request.</b> An unreadable declaration, a resolver outage, a missing
 * object, a bug — each declines the one path with a sentence saying why, and the fold answers the
 * 409 it would have answered without the rule. One instance per fold: it caches each head's
 * declarations, each name's resolution and each sibling's tag index for that request only, so a
 * later fold always sees new releases.
 */
final class GitlinkPinRule {

  private static final Logger LOG = Logger.getLogger(GitlinkPinRule.class);

  /** The calendar version a release tag is spelled in: {@code 2026.1018.135253}. */
  static final Pattern CALVER = Pattern.compile("^\\d{4}\\.\\d{3,4}\\.\\d{1,6}$");

  private static final String GITMODULES = Constants.DOT_GIT_MODULES;

  /** A {@code .gitmodules} is a few kilobytes; anything near this is not one worth parsing. */
  private static final int MAX_GITMODULES_BYTES = 1024 * 1024;

  /** Marks a path two submodule sections both claim, which names no one sibling. */
  private static final String AMBIGUOUS = "\u0000ambiguous";

  /**
   * What the rule came to for one path: a decision, or the one sentence saying why not. Exactly one
   * of the two is non-null.
   */
  record Verdict(ResolvedGitlink decided, String declined) {}

  private final ObjectReader wrapper;
  private final String projectId;
  private final Instance<RepositoryNameResolver> names;
  private final GitRepositoryProvider repositories;

  private final Map<ObjectId, Map<String, String>> declarations = new HashMap<>();
  private final Map<String, Optional<String>> resolved = new HashMap<>();
  private final Map<String, Map<ObjectId, String>> tagIndexes = new HashMap<>();

  /**
   * @param wrapper reads the superproject's objects — the fold's own reader
   * @param projectId the project a declared name is resolved in; {@code null} declines every path
   */
  GitlinkPinRule(
      ObjectReader wrapper,
      String projectId,
      Instance<RepositoryNameResolver> names,
      GitRepositoryProvider repositories) {
    this.wrapper = wrapper;
    this.projectId = projectId == null || projectId.isBlank() ? null : projectId;
    this.names = names;
    this.repositories = repositories;
  }

  /**
   * Decides the gitlink conflict at {@code path}, or says why not. Never throws.
   *
   * @param ours the accumulator's pin, or {@code null} where it has none
   * @param theirs the pin of the head being folded in, or {@code null}
   * @param head the head being folded in at this step, whose {@code .gitmodules} is read
   * @param spelling that head as the caller named it, for the sentence
   */
  Verdict decide(String path, String ours, String theirs, RevCommit head, String spelling) {
    try {
      return decideOrDecline(path, ours, theirs, head, spelling);
    } catch (RepositoryNameResolver.Unavailable e) {
      return no(path, "the repositories of this project could not be looked up: " + e.getMessage());
    } catch (IOException | RuntimeException e) {
      LOG.warnf(e, "the gitlink-pin rule could not decide %s; it stands as a conflict", path);
      return no(path, "the rule could not read what it needs: " + e);
    }
  }

  private Verdict decideOrDecline(
      String path, String ours, String theirs, RevCommit head, String spelling)
      throws IOException {
    if (ours == null || theirs == null) {
      return no(path, "one side does not have the submodule at all, which is a person's call");
    }
    if (ours.equals(theirs)) {
      return no(path, "both sides pin the same commit, so there is nothing to decide");
    }
    Map<String, String> declared = declarationsAt(head);
    if (declared == null) {
      return no(path, "the submodule declarations at " + spelling + " could not be parsed");
    }
    String name = declared.get(path);
    if (name == null) {
      return no(path, "no submodule is declared there at " + spelling);
    }
    if (AMBIGUOUS.equals(name)) {
      return no(path, "two submodules are declared there at " + spelling);
    }
    if (projectId == null) {
      return no(path, "the fold names no project, so '" + name + "' resolves to no repository");
    }
    String siblingId = siblingOf(name);
    if (siblingId == null) {
      return no(path, "'" + name + "' names no repository of this project");
    }
    try (Repository sibling = repositories.open(siblingId)) {
      if (sibling == null) {
        return no(path, "'" + name + "' names no repository this host holds");
      }
      try (RevWalk walk = new RevWalk(sibling)) {
        Map<ObjectId, String> tags = tagIndex(siblingId, sibling, walk);
        ObjectId oursId = ObjectId.fromString(ours);
        ObjectId theirsId = ObjectId.fromString(theirs);
        String oursTag = tags.get(oursId);
        if (oursTag == null) {
          return no(path, shortSha(ours) + " is not a release tag of '" + name + "'");
        }
        String theirsTag = tags.get(theirsId);
        if (theirsTag == null) {
          return no(path, shortSha(theirs) + " is not a release tag of '" + name + "'");
        }
        int order = compare(oursTag, theirsTag);
        if (order == 0) {
          return no(path, "both releases of '" + name + "' carry the same version");
        }
        boolean theirsIsLater = order < 0;
        ObjectId laterId = theirsIsLater ? theirsId : oursId;
        ObjectId earlierId = theirsIsLater ? oursId : theirsId;
        String laterTag = theirsIsLater ? theirsTag : oursTag;
        String earlierTag = theirsIsLater ? oursTag : theirsTag;
        RevCommit later;
        RevCommit earlier;
        try {
          later = walk.parseCommit(laterId);
          earlier = walk.parseCommit(earlierId);
        } catch (MissingObjectException e) {
          return no(path, "the history of '" + name + "' is missing " + shortSha(e.getObjectId()));
        }
        if (!walk.isMergedInto(earlier, later)) {
          walk.reset();
          if (walk.isMergedInto(later, earlier)) {
            return no(
                path,
                earlierTag
                    + " of '"
                    + name
                    + "' contains "
                    + laterTag
                    + " but carries the lower version, so ancestry and version disagree");
          }
          return no(
              path,
              laterTag
                  + " of '"
                  + name
                  + "' is the later release but does not contain "
                  + earlierTag
                  + ", so pinning it would drop released work");
        }
        return new Verdict(
            new ResolvedGitlink(path, name, earlierId.name(), laterId.name(), laterTag), null);
      }
    }
  }

  /**
   * Path → section name for every submodule {@code head}'s {@code .gitmodules} declares, empty when
   * it has none, {@code null} when it is there and not a config file.
   */
  private Map<String, String> declarationsAt(RevCommit head) throws IOException {
    ObjectId key = head.toObjectId();
    if (declarations.containsKey(key)) {
      return declarations.get(key);
    }
    Map<String, String> declared = new LinkedHashMap<>();
    try (TreeWalk tree = TreeWalk.forPath(wrapper, GITMODULES, head.getTree())) {
      if (tree != null && FileMode.REGULAR_FILE.equals(tree.getRawMode(0))) {
        byte[] bytes =
            wrapper.open(tree.getObjectId(0), Constants.OBJ_BLOB).getCachedBytes(MAX_GITMODULES_BYTES);
        Config config = new Config();
        try {
          config.fromText(new String(bytes, StandardCharsets.UTF_8));
        } catch (ConfigInvalidException e) {
          declared = null;
        }
        if (declared != null) {
          for (String section : config.getSubsections("submodule")) {
            String declaredPath = config.getString("submodule", section, "path");
            if (declaredPath != null && !declaredPath.isBlank()) {
              declared.merge(declaredPath, section, (first, second) -> AMBIGUOUS);
            }
          }
        }
      }
    }
    declarations.put(key, declared);
    return declared;
  }

  /**
   * The sibling's storage id, or {@code null} when the project has no repository by that name. An
   * outage throws {@link RepositoryNameResolver.Unavailable} and is not cached.
   */
  private String siblingOf(String name) {
    Optional<String> cached = resolved.get(name);
    if (cached == null) {
      if (names == null || !names.isResolvable()) {
        cached = Optional.empty();
      } else {
        Optional<String> answer = names.get().resolveRepositoryId(projectId, name);
        cached = answer == null ? Optional.empty() : answer;
      }
      // The id touches the store next, so it is held to the same rule as one from a url.
      cached = cached.filter(RepositoryRefsResource::isValidRepoId);
      resolved.put(name, cached);
    }
    return cached.orElse(null);
  }

  /**
   * Commit → the highest calendar version tagging it, over every {@code refs/tags/} the sibling
   * holds. Annotated tags are peeled; a tag on something that is not a commit, or whose object is
   * missing, tags nothing here.
   */
  private Map<ObjectId, String> tagIndex(String siblingId, Repository sibling, RevWalk walk)
      throws IOException {
    Map<ObjectId, String> index = tagIndexes.get(siblingId);
    if (index != null) {
      return index;
    }
    index = new HashMap<>();
    for (Ref ref : sibling.getRefDatabase().getRefsByPrefix(Constants.R_TAGS)) {
      String version = ref.getName().substring(Constants.R_TAGS.length());
      if (!CALVER.matcher(version).matches() || ref.getObjectId() == null) {
        continue;
      }
      RevObject peeled;
      try {
        peeled = walk.peel(walk.parseAny(ref.getObjectId()));
      } catch (MissingObjectException e) {
        continue;
      }
      if (peeled instanceof RevCommit commit) {
        index.merge(
            commit.toObjectId(), version, (held, other) -> compare(held, other) >= 0 ? held : other);
      }
    }
    tagIndexes.put(siblingId, index);
    return index;
  }

  /**
   * Orders two calendar versions numerically, segment by segment — {@code 2026.930.9 < 2026.930.10
   * < 2026.1001.5} — never as strings, which would put {@code 2026.1001} before {@code 2026.930}.
   * Both must match {@link #CALVER}.
   */
  static int compare(String a, String b) {
    String[] left = a.split("\\.");
    String[] right = b.split("\\.");
    for (int i = 0; i < Math.max(left.length, right.length); i++) {
      long l = i < left.length ? Long.parseLong(left[i]) : 0;
      long r = i < right.length ? Long.parseLong(right[i]) : 0;
      if (l != r) {
        return Long.compare(l, r);
      }
    }
    return 0;
  }

  private static Verdict no(String path, String reason) {
    return new Verdict(
        null, "the conflict at " + path + " is not one this service can decide: " + reason);
  }

  private static String shortSha(String sha) {
    return sha.length() <= 10 ? sha : sha.substring(0, 10);
  }

  private static String shortSha(ObjectId id) {
    return id == null ? "an object" : shortSha(id.name());
  }
}
