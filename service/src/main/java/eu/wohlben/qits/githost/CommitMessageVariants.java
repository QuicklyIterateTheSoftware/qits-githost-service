package eu.wohlben.qits.githost;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The one commit-message pattern this host accepts, as a closed list of variants (qits-706). A
 * commit is accepted when its first line — and, for {@link #MERGE}, its parent count — matches one
 * of them, and refused otherwise. There is no other way in: no per-repository file, no server
 * switch, no exemption by pusher identity, no push option.
 *
 * <p>{@link #classify} tries the variants in declaration order and the first match wins, so the
 * order is part of the contract: a merge is {@link #MERGE} whatever its message says, and {@code
 * bump(…)}, {@code release(…)} and {@code chore(platform)} are told apart from a {@link #SUBJECT}
 * before it is tried (none of them could match it anyway — their scopes carry no {@code -<n>}).
 *
 * <p>The refusal both write paths give — the push's {@code remote:} lines and {@code POST
 * …/commits}' 422 — lists every variant with its example out of {@link #acceptedForms()}, so the
 * text cannot drift from what is enforced.
 *
 * <p>Syntax only: an id in a {@link #SUBJECT} is never looked up, and nothing here calls
 * qits-projects.
 */
public enum CommitMessageVariants {

  /**
   * A commit with two or more parents, whatever its message: merge messages are machine-written
   * (the fold, {@code git merge}). The commits a merge brings in are checked on their own.
   */
  MERGE(
      "any commit with two or more parents, whatever its message",
      "Merge branch 'feature' into main",
      (firstLine, parents) -> parents >= 2),

  /** A qits-maintenance bump step: {@code bump(<group>): <n> dependencies}. */
  BUMP(
      "bump(<group>): <n> dependencies",
      "bump(targeted): 3 dependencies",
      matching("^bump\\([0-9A-Za-z._-]+\\): [0-9]+ dependencies$")),

  /**
   * A release's version stamp, which qits-projects' release executor commits through {@code POST
   * …/commits}: {@code release(<version>): message}, the version three dot-separated numbers.
   */
  RELEASE(
      "release(<version>): message",
      "release(2026.1104.22323): stamp the version",
      matching("^release\\([0-9]+\\.[0-9]+\\.[0-9]+\\): .+$")),

  /** Platform housekeeping that belongs to no ticket: {@code chore(platform): message}. */
  PLATFORM(
      "chore(platform): message",
      "chore(platform): initialize the repository template skeleton",
      matching("^chore\\(platform\\): \\S.*$")),

  /**
   * Work that names what it belongs to: {@code term(<project>-<n>): message}, or several ids
   * separated by a comma and an optional space. A strict subset of qits-projects' reader ({@code
   * CommitSubjectEntities}: head {@code ^([^()\s:]*)\(([^()]+)\)(!?):}, which then splits the
   * head's group 2 on {@code ,} and strips each part; scope {@code
   * ^([A-Za-z0-9][A-Za-z0-9-]*)-([0-9]{1,18})$}, which every stripped part must match), so every
   * subject accepted here names only entities the reader would also find.
   */
  SUBJECT(
      "term(<project>-<n>): message, or term(<project>-<n>, <project>-<n>): message",
      "feat(qits-1337): refuse malformed commit subjects",
      (firstLine, parents) -> Grammar.SUBJECT.matcher(firstLine).matches());

  /** The patterns a constant cannot declare as its own static fields. */
  static final class Grammar {
    private Grammar() {}

    /** One qualified id, {@code <project>-<n>}: the reader's scope grammar. */
    static final String ID = "[A-Za-z0-9][A-Za-z0-9-]*-[0-9]{1,18}";

    /** {@link CommitMessageVariants#SUBJECT}'s first line. */
    static final Pattern SUBJECT =
        Pattern.compile("^[A-Za-z][A-Za-z0-9_/.-]*\\((" + ID + "(?:, *" + ID + ")*)\\)!?: \\S.*$");
  }

  /** What a variant reads of a commit: its message's first line and its parent count. */
  @FunctionalInterface
  interface Predicate {
    boolean test(String firstLine, int parents);
  }

  private final String form;
  private final String example;
  private final Predicate predicate;

  CommitMessageVariants(String form, String example, Predicate predicate) {
    this.form = form;
    this.example = example;
    this.predicate = predicate;
  }

  /** A predicate on the first line alone, the whole line matched against {@code regex}. */
  private static Predicate matching(String regex) {
    Pattern pattern = Pattern.compile(regex);
    return (firstLine, parents) -> pattern.matcher(firstLine).matches();
  }

  /** The shape a person reads in a refusal. */
  public String form() {
    return form;
  }

  /** A message this variant accepts — the refusal shows it, and a test proves it is accepted. */
  public String example() {
    return example;
  }

  /**
   * Whether this variant accepts a commit whose message starts with {@code firstLine} and which has
   * {@code parents} parents. {@link #MERGE} reads only the parent count; every other variant reads
   * only the first line, and {@link #classify} only asks it after {@link #MERGE} declined.
   */
  public boolean accepts(String firstLine, int parents) {
    return predicate.test(firstLine, parents);
  }

  /** The first variant that accepts this commit, or empty when none does and it is refused. */
  public static Optional<CommitMessageVariants> classify(String message, int parents) {
    String first = firstLine(message);
    for (CommitMessageVariants variant : values()) {
      if (variant.accepts(first, parents)) {
        return Optional.of(variant);
      }
    }
    return Optional.empty();
  }

  /** Everything before the first newline; the whole message when there is none. */
  static String firstLine(String message) {
    if (message == null) {
      return "";
    }
    int newline = message.indexOf('\n');
    return newline < 0 ? message : message.substring(0, newline);
  }

  /**
   * Every accepted form with its example, then what a {@link #SUBJECT}'s id is — the part of a
   * refusal both write paths share. Generated from the constants, in classification order.
   */
  public static List<String> acceptedForms() {
    List<String> lines = new ArrayList<>();
    lines.add("Accepted forms of a commit message's first line (the first that matches counts):");
    for (CommitMessageVariants variant : values()) {
      lines.add("  " + variant.name() + ": " + variant.form());
      lines.add("    for example: " + variant.example());
    }
    lines.add(
        "In " + SUBJECT.name() + ", <project>-<n> is the qualified id of the ticket, epic or task"
            + " the work belongs to, as shown in its dispatch prompt and by `qits work`. Several ids"
            + " may be given, separated by a comma and an optional space, when a commit belongs to"
            + " more than one.");
    return lines;
  }
}
