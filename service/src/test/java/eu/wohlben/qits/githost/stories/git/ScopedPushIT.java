package eu.wohlben.qits.githost.stories.git;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.githost.api.TokenValidationBootstrapIT;
import eu.wohlben.qits.githost.stories.support.MockProjects;
import eu.wohlben.qits.githost.stories.support.StoryAccessLog;
import eu.wohlben.qits.githost.stories.support.StoryOrigin;
import eu.wohlben.qits.githost.stories.support.StoryTarget;
import eu.wohlben.qits.githost.stories.support.StoryTools;
import eu.wohlben.qits.servicemock.idp.MockIdp;
import eu.wohlben.qits.userflows.Commands;
import eu.wohlben.qits.userflows.Interactions;
import eu.wohlben.qits.userflows.UserStory;
import eu.wohlben.qits.userflows.UserStoryDescription;
import eu.wohlben.qits.userflows.report.ReportAssertions;
import eu.wohlben.qits.userflows.report.UserflowReport;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusIntegrationTest;
import io.quarkus.test.junit.TestProfile;
import java.net.URL;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.condition.EnabledIf;

/**
 * What a credential may push, against the packaged process with the OIDC tenant on (contract C3).
 *
 * <p>The {@code @QuarkusTest} suite proves the rules with a test mechanism in place of OIDC. Only
 * here do the tokens pass real validation, so only here is it proved that a person's CLI token
 * (audience {@code qits-platform}) gets in at all, and that a {@code git_refs} JSON array is read
 * the way quarkus-oidc hands it over.
 */
@QuarkusIntegrationTest
@TestProfile(TokenValidationBootstrapIT.PackagedWithMockIdp.class)
@EnabledIf("eu.wohlben.qits.githost.stories.support.StoryTools#gitAndCurlPresent")
public class ScopedPushIT {

  static final String CATEGORY = "git";
  static final String PERSON_SLUG = "a-person-s-cli-token-pushes-only-external-branches";
  static final String AGENT_SLUG = "an-agent-pushes-only-the-branches-it-was-given";

  static final String PERSON = "a person";
  static final String AGENT = "an agent";

  static final String PERSON_REPO = "person-notes";
  static final String AGENT_REPO = "ticket-work";

  static final String EXTERNAL_REF = "refs/heads/external/alice/notes";
  static final String TICKET_REF = "refs/heads/ticket/t-1";

  private static String personToken;
  private static String agentToken;

  @TestHTTPResource("/")
  URL root;

  @BeforeAll
  static void tapBothEndsOfTheNetwork() {
    StoryAccessLog.install();
    MockProjects.installSource();
  }

  @UserStory(value = "A person's CLI token pushes only external branches", category = "git")
  @UserStoryDescription(
      """
      A person pushes with the token the `qits` CLI holds. That token is minted for the platform
      audience, `qits-platform`, not for the git host's own, and the git host accepts it. It says
      `credential_type=cli`, so the git host treats it as a person: whatever roles the person has —
      here `qits:admin` — a person pushes only `refs/heads/external/*`. The push to `main` is
      refused with a message that names the ref and the scope, and `main` does not move; the push
      to an external branch lands.
      """)
  void aPersonsCliTokenPushesOnlyExternalBranches(Interactions story, Commands commands) {
    StoryTarget target = new StoryTarget(root);
    String seeded = seed(target, PERSON_REPO);
    personToken =
        MockIdp.attach()
            .token()
            .subject("alice")
            .audience("qits-platform")
            .groups("qits:admin")
            .claim("credential_type", "cli")
            .mint();
    commands.redact(personToken);
    StoryAccessLog.actor(PERSON);
    prepare(commands);

    story
        .note("the CLI token names the audience qits-platform, holds qits:admin and says"
            + " credential_type=cli")
        .as("token-held");
    String pushed = cloneAndCommit(commands, target, PERSON_REPO, personToken);

    commands
        .expectExit(1)
        .run("{} -c {} push origin {}", StoryTools.git(), StoryOrigin.authConfig(personToken), "main")
        .as("main-refused");
    assertTrue(
        commands.lastOutput().contains(
            "refs/heads/main is outside the push scope: a person's credential may push only"
                + " refs/heads/external/*"),
        commands::lastOutput);

    commands
        .run(
            "{} -c {} push origin {}",
            StoryTools.git(),
            StoryOrigin.authConfig(personToken),
            "HEAD:" + EXTERNAL_REF)
        .as("external-branch-pushed");

    commands
        .run(
            "{} -c {} ls-remote {}",
            StoryTools.git(),
            StoryOrigin.authConfig(personToken),
            target.cloneUrl(PERSON_REPO))
        .as("origin-refs-read");
    String refs = commands.lastOutput();
    assertTrue(refs.contains(pushed + "\t" + EXTERNAL_REF), refs);
    assertTrue(refs.contains(seeded + "\trefs/heads/main"), refs);

    story.note("main still names the first commit; the external branch names the new one")
        .as("scope-verified");
    StoryAccessLog.awaitLogged("POST " + StoryTarget.clonePath(PERSON_REPO) + "/git-receive-pack");
  }

  @UserStory(value = "An agent pushes only the branches it was given", category = "git")
  @UserStoryDescription(
      """
      An agent works on one ticket. Its token lists the refs it may push in the `git_refs` claim —
      here only `refs/heads/ticket/t-1` — and still carries the roles of the service that
      commissioned it, `qits:admin` and `qits:system`. Those roles do not widen the list: the push
      to its own branch lands, and the pushes to an epic branch and to `main` are refused, each
      with a message that names the ref and the list.
      """)
  void anAgentPushesOnlyTheBranchesItWasGiven(Interactions story, Commands commands) {
    StoryTarget target = new StoryTarget(root);
    String seeded = seed(target, AGENT_REPO);
    agentToken =
        MockIdp.attach()
            .token()
            .subject("dyn-workspace-t-1")
            .audience(StoryOrigin.AUDIENCE)
            .groups("qits:admin", "qits:system")
            .claim("context_kind", "workspace")
            .claim("git_refs", List.of(TICKET_REF))
            .mint();
    commands.redact(agentToken);
    StoryAccessLog.actor(AGENT);
    prepare(commands);

    story.note("the agent's token lists git_refs=[refs/heads/ticket/t-1] and holds qits:system")
        .as("token-held");
    String pushed = cloneAndCommit(commands, target, AGENT_REPO, agentToken);

    commands
        .run(
            "{} -c {} push origin {}",
            StoryTools.git(),
            StoryOrigin.authConfig(agentToken),
            "HEAD:" + TICKET_REF)
        .as("own-branch-pushed");

    commands
        .expectExit(1)
        .run(
            "{} -c {} push origin {}",
            StoryTools.git(),
            StoryOrigin.authConfig(agentToken),
            "HEAD:refs/heads/epic/e-1")
        .as("epic-branch-refused");
    assertTrue(
        commands.lastOutput().contains(
            "refs/heads/epic/e-1 is outside the push scope: this credential may push only "
                + TICKET_REF),
        commands::lastOutput);

    commands
        .expectExit(1)
        .run("{} -c {} push origin {}", StoryTools.git(), StoryOrigin.authConfig(agentToken), "main")
        .as("main-refused");
    assertTrue(
        commands.lastOutput().contains("refs/heads/main is outside the push scope"),
        commands::lastOutput);

    commands
        .run(
            "{} -c {} ls-remote {}",
            StoryTools.git(),
            StoryOrigin.authConfig(agentToken),
            target.cloneUrl(AGENT_REPO))
        .as("origin-refs-read");
    String refs = commands.lastOutput();
    assertTrue(refs.contains(pushed + "\t" + TICKET_REF), refs);
    assertTrue(refs.contains(seeded + "\trefs/heads/main"), refs);
    assertFalse(refs.contains("refs/heads/epic/e-1"), refs);

    story.note("only the ticket branch moved").as("scope-verified");
    StoryAccessLog.awaitLogged("POST " + StoryTarget.clonePath(AGENT_REPO) + "/git-receive-pack");
  }

  /** Provisions the repository over the storage scheme, which the diagram leaves out. */
  private static String seed(StoryTarget target, String repoName) {
    return StoryOrigin.provisionSeeded(
        target,
        MockProjects.repositoryId(repoName),
        StoryOrigin.bearer(),
        Map.of("README.md", "# " + repoName + "\n"),
        "the first commit");
  }

  private static void prepare(Commands commands) {
    commands.env("HOME", commands.workDir().toAbsolutePath().toString());
    commands.env("GIT_TERMINAL_PROMPT", "0");
  }

  /** Clones as the story's credential and commits one file; returns the new commit. */
  private static String cloneAndCommit(
      Commands commands, StoryTarget target, String repoName, String token) {
    commands
        .run(
            "{} -c {} clone {} {}",
            StoryTools.git(),
            StoryOrigin.authConfig(token),
            target.cloneUrl(repoName),
            "work")
        .as("repository-cloned");
    commands.in("work");
    commands.file("NOTES.md", "# Notes\n\n- pushed within the scope\n").as("change-written");
    commands.run("{} add {}", StoryTools.git(), "NOTES.md");
    commands
        .run(
            "{} -c user.email={} -c user.name={} commit -q -m {}",
            StoryTools.git(),
            "alice@qits.local",
            "alice",
            "a change")
        .as("change-committed");
    commands.run("{} rev-parse HEAD", StoryTools.git());
    return commands.lastOutput().strip();
  }

  @AfterAll
  static void storyReportsAreComplete() {
    if (!StoryTools.gitAndCurlPresent()) {
      return;
    }
    assertStory(PERSON_SLUG, PERSON, PERSON_REPO, personToken);
    ReportAssertions.assertCommand(CATEGORY, PERSON_SLUG, "push origin main", 1);
    ReportAssertions.assertCommand(CATEGORY, PERSON_SLUG, "push origin HEAD:" + EXTERNAL_REF, 0);
    ReportAssertions.assertStepId(CATEGORY, PERSON_SLUG, "external-branch-pushed");

    assertStory(AGENT_SLUG, AGENT, AGENT_REPO, agentToken);
    ReportAssertions.assertCommand(CATEGORY, AGENT_SLUG, "push origin HEAD:" + TICKET_REF, 0);
    ReportAssertions.assertCommand(CATEGORY, AGENT_SLUG, "push origin HEAD:refs/heads/epic/e-1", 1);
    ReportAssertions.assertCommand(CATEGORY, AGENT_SLUG, "push origin main", 1);
    ReportAssertions.assertStepId(CATEGORY, AGENT_SLUG, "own-branch-pushed");
    ReportAssertions.assertStepId(CATEGORY, AGENT_SLUG, "epic-branch-refused");
  }

  private static void assertStory(String slug, String actor, String repoName, String token) {
    ReportAssertions.assertComplete(CATEGORY, slug, UserflowReport.PASSED);
    ReportAssertions.assertStepId(CATEGORY, slug, "token-held");
    ReportAssertions.assertStepId(CATEGORY, slug, "repository-cloned");
    ReportAssertions.assertStepId(CATEGORY, slug, "main-refused");
    ReportAssertions.assertStepId(CATEGORY, slug, "origin-refs-read");
    ReportAssertions.assertStepId(CATEGORY, slug, "scope-verified");
    String repo = StoryTarget.clonePath(repoName);
    // A refused push is still a 200: git reports the refusal inside the protocol, per ref.
    ReportAssertions.assertEdge(
        CATEGORY,
        slug,
        "git",
        actor,
        StoryAccessLog.SERVICE,
        "GET " + repo + "/info/refs?service=git-receive-pack -> 200");
    ReportAssertions.assertEdge(
        CATEGORY, slug, "git", actor, StoryAccessLog.SERVICE, "POST " + repo + "/git-receive-pack -> 200");
    ReportAssertions.assertEdge(
        CATEGORY,
        slug,
        "http",
        StoryAccessLog.SERVICE,
        MockProjects.SERVICE_NAME,
        MockProjects.lookupLabel(repoName, 200));
    ReportAssertions.assertOnlyEdgesFrom(CATEGORY, slug, List.of(actor, StoryAccessLog.SERVICE));
    ReportAssertions.assertNotLeaked(CATEGORY, slug, token);
  }
}
