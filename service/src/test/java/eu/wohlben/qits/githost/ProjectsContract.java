package eu.wohlben.qits.githost;

import static org.junit.jupiter.api.Assertions.assertEquals;

import au.com.dius.pact.consumer.dsl.PactBuilder;
import au.com.dius.pact.core.model.PactSpecVersion;
import au.com.dius.pact.core.model.V4Pact;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.githost.contracts.ProviderGoldenMasters;
import eu.wohlben.qits.githost.contracts.ProviderGoldenMasters.Call;
import eu.wohlben.qits.githost.contracts.ProviderGoldenMasters.Trigger;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiConsumer;

/**
 * <b>What qits-githost asks qits-projects, and why</b> (ticket qits-1149) — the one table both
 * {@code ProjectsConsumerPactTest} and {@code ProjectsPactFileTest} are built from.
 *
 * <p>qits-githost makes ONE call to qits-projects: {@link HttpRepositoryNameResolver}'s {@code GET
 * /projects/api/projects/{projectId}/repositories/by-name/{repoName}} ({@code
 * resolveRepositoryName}). Five raw routes of {@link GitHostRoutes} reach it — every door of the
 * public, name-addressed scheme — so the table holds one row per (route, answer). It reads two
 * answers: 200, where it reads {@code repositoryId} and nothing else, and 404, where it reads
 * nothing.
 *
 * <p><b>{@link #CASES} are the rows the provider can verify today</b> and go into the committed
 * pact. {@link #PENDING} are rows that wait on a provider state qits-projects does not record yet;
 * they stay out of the pact and their test is disabled, naming the state.
 */
final class ProjectsContract {

  static final ProviderGoldenMasters PROJECTS = ProviderGoldenMasters.PROJECTS;

  /** Recorded by qits-projects (it records getProject's 404 in it). */
  static final String NO_PROJECT_WITH_THE_GIVEN_ID = "no project with the given id";

  /**
   * NOT recorded yet: qits-projects must record {@code resolveRepositoryName} in this state, with a
   * {@code repoName} param and {@code repositoryId} under {@code frozen.ids}.
   */
  static final String A_REPOSITORY_EXISTS = "a repository exists";

  static final String RESOLVE_REPOSITORY_NAME = "resolveRepositoryName";

  static final String PATH = "/projects/api/projects/{projectId}/repositories/by-name/{repoName}";

  /** The name the 404 rows look up: one the state's project cannot hold, as it holds none. */
  static final String UNKNOWN_NAME = "no-such-repository";

  static final String PENDING_REASON =
      "needs provider state '" + A_REPOSITORY_EXISTS + "' for " + RESOLVE_REPOSITORY_NAME
          + " in qits-projects-service";

  /** Every route of the name-addressed scheme; each one resolves the name before it serves. */
  static final List<Trigger> TRIGGERS =
      List.of(
          Trigger.operation("GET /git/{projectId}/{repoName}/info/refs"),
          Trigger.operation("POST /git/{projectId}/{repoName}/git-upload-pack"),
          Trigger.operation("POST /git/{projectId}/{repoName}/git-receive-pack"),
          Trigger.operation("GET /git/{projectId}/{repoName}/blob/{rev}/{path}"),
          Trigger.operation("GET /git/{projectId}/{repoName}/tree/{rev}/{path}"));

  /** One (trigger, state, call), what the consumer reads of the answer, and what it does. */
  record Case(
      Trigger trigger,
      String state,
      Call call,
      List<String> consumes,
      BiConsumer<HttpRepositoryNameResolver, Map<String, String>> run) {

    String description() {
      return ProviderGoldenMasters.description(call.operationId(), trigger);
    }
  }

  /** 404: "no such name". The resolver reads nothing of the body and answers empty. */
  private static final BiConsumer<HttpRepositoryNameResolver, Map<String, String>> MISS =
      (resolver, params) ->
          assertEquals(
              Optional.empty(), resolver.resolveRepositoryId(params.get("projectId"), UNKNOWN_NAME));

  static final List<Case> CASES =
      TRIGGERS.stream()
          .map(
              trigger ->
                  new Case(
                      trigger,
                      NO_PROJECT_WITH_THE_GIVEN_ID,
                      new Call(
                          RESOLVE_REPOSITORY_NAME, "GET", PATH, Map.of("repoName", UNKNOWN_NAME), 404),
                      List.of(),
                      MISS))
          .toList();

  /** 200: the name resolves. The resolver reads {@code repositoryId} and answers it. */
  static final List<Case> PENDING =
      TRIGGERS.stream()
          .map(
              trigger ->
                  new Case(
                      trigger,
                      A_REPOSITORY_EXISTS,
                      new Call(RESOLVE_REPOSITORY_NAME, "GET", PATH, Map.of(), 200),
                      List.of("$.repositoryId"),
                      (resolver, params) ->
                          assertEquals(
                              Optional.of(params.get("repositoryId")),
                              resolver.resolveRepositoryId(
                                  params.get("projectId"), params.get("repoName")))))
          .toList();

  private ProjectsContract() {}

  /** The committed contract: {@link #CASES} as one V4 pact. */
  static V4Pact pact() {
    return pact(CASES);
  }

  static V4Pact pact(List<Case> cases) {
    PactBuilder builder =
        new PactBuilder(ProviderGoldenMasters.CONSUMER, PROJECTS.provider(), PactSpecVersion.V4);
    for (Case c : cases) {
      PROJECTS.interaction(builder, c.state(), c.call(), c.trigger(), null, c.consumes());
    }
    return builder.toPact();
  }

  /** The real resolver, pointed at {@code baseUrl} the way production config points it. */
  static HttpRepositoryNameResolver resolverAgainst(String baseUrl) {
    HttpRepositoryNameResolver resolver = new HttpRepositoryNameResolver();
    resolver.resolverUrl = Optional.of(baseUrl + "/projects/api/projects");
    resolver.objectMapper = new ObjectMapper();
    return resolver;
  }
}
