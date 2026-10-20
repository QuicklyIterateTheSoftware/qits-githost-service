package eu.wohlben.qits.githost.contracts;

import static org.junit.jupiter.api.Assertions.fail;

import au.com.dius.pact.core.model.Interaction;
import au.com.dius.pact.core.model.Pact;
import au.com.dius.pact.core.model.ProviderState;
import au.com.dius.pact.provider.junit5.HttpTestTarget;
import au.com.dius.pact.provider.junit5.PactVerificationContext;
import au.com.dius.pact.provider.junit5.PactVerificationInvocationContextProvider;
import au.com.dius.pact.provider.junitsupport.Provider;
import au.com.dius.pact.provider.junitsupport.State;
import au.com.dius.pact.provider.junitsupport.loader.PactSource;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.net.URL;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestTemplate;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * <b>Verifies every consumer's pact against the running provider</b>, the way qits-projects-service
 * does.
 *
 * <p>The pacts come off the test classpath: each consumer publishes its pact as a jar holding
 * {@code pacts/<consumer>_qits-githost-service.json} (repository names on both sides), this repo
 * pins that jar as a test dependency, and qits-maintenance bumps the pin when the consumer releases
 * a changed pact. {@link ClasspathPactLoader} finds them all.
 *
 * <p><b>The pinned pacts</b> are the test-scope {@code eu.wohlben.qits:<consumer>-pacts-qits-githost-service}
 * dependencies in {@code service/pom.xml} — today qits-projects-service's, qits-ci-service's,
 * qits-deployments-service's, qits-maintenance-service's and qits-bootstrap-cli's.
 * {@link ClasspathPactLoader#REQUIRED} is true, so a classpath that lost them fails rather than
 * verifying nothing. A consumer that starts committing {@code pacts/<consumer>_qits-githost-service.json} is
 * pinned there once its jar is published; a state its pact names that this class does not answer
 * fails {@link #target} and is added to {@link ProviderStates}, with its golden master.
 *
 * <p>Each interaction runs against this {@code @QuarkusTest} application over real HTTP, as the
 * {@code %test} synthetic user — exactly as {@link GoldenMasterRecordingTest}'s calls run. Every
 * {@code @State} method delegates to {@link ProviderStates}; {@link #target} fails an unknown state,
 * and an interaction without {@code comments.references.qits-call} or {@code qits-trigger}.
 */
@QuarkusTest
@Provider(ConsumerPactVerificationTest.PROVIDER)
@PactSource(ClasspathPactLoader.class)
class ConsumerPactVerificationTest {

  /** The provider's name in a pact: the repository name, not the application name. */
  static final String PROVIDER = "qits-githost-service";

  static {
    // pact-jvm reports usage metrics over the network unless told not to; a test never should.
    System.setProperty("pact_do_not_track", "true");
  }

  @Inject ProviderStates states;

  @TestHTTPResource("/")
  URL base;

  @BeforeEach
  void target(PactVerificationContext context, Pact pact, Interaction interaction) {
    String consumer = pact.getConsumer().getName();
    for (ProviderState state : interaction.getProviderStates()) {
      if (!states.names().contains(state.getName())) {
        fail(
            "Consumer '"
                + consumer
                + "' needs the provider state '"
                + state.getName()
                + "' (interaction '"
                + interaction.getDescription()
                + "'), which qits-githost does not answer for — it answers for "
                + states.names());
      }
    }
    var references = interaction.getComments().get("references");
    for (String key : List.of("qits-call", "qits-trigger")) {
      if (references == null || !references.isObject() || !references.asObject().has(key)) {
        fail(
            "Consumer '"
                + consumer
                + "' interaction '"
                + interaction.getDescription()
                + "' carries no comments.references."
                + key);
      }
    }
    context.setTarget(new HttpTestTarget(base.getHost(), base.getPort()));
  }

  @TestTemplate
  @ExtendWith(PactVerificationInvocationContextProvider.class)
  void consumerPactHolds(PactVerificationContext context) {
    context.verifyInteraction();
  }

  // --- the states: each one line into the registry -------------------------------------------

  @State(ProviderStates.A_REPOSITORY_WITH_COUNTED_LINES)
  Map<String, String> aRepositoryWithCountedLines() {
    return states.params(ProviderStates.A_REPOSITORY_WITH_COUNTED_LINES);
  }

  @State(ProviderStates.A_REPOSITORY_NOT_COUNTED_YET)
  Map<String, String> aRepositoryNotCountedYet() {
    return states.params(ProviderStates.A_REPOSITORY_NOT_COUNTED_YET);
  }

  @State(ProviderStates.A_REPOSITORY_WITH_NO_COMMIT)
  Map<String, String> aRepositoryWithNoCommit() {
    return states.params(ProviderStates.A_REPOSITORY_WITH_NO_COMMIT);
  }

  @State(ProviderStates.NO_REPOSITORY_WITH_THE_GIVEN_ID)
  Map<String, String> noRepositoryWithTheGivenId() {
    return states.params(ProviderStates.NO_REPOSITORY_WITH_THE_GIVEN_ID);
  }

  @State(ProviderStates.TWO_REPOSITORIES_ONE_COUNTED)
  Map<String, String> twoRepositoriesOneCounted() {
    return states.params(ProviderStates.TWO_REPOSITORIES_ONE_COUNTED);
  }

  @State(ProviderStates.A_REPOSITORY_COUNTED_AT_AN_OLDER_COMMIT)
  Map<String, String> aRepositoryCountedAtAnOlderCommit() {
    return states.params(ProviderStates.A_REPOSITORY_COUNTED_AT_AN_OLDER_COMMIT);
  }

  @State(ProviderStates.A_REPOSITORY_EXISTS)
  Map<String, String> aRepositoryExists() {
    return states.params(ProviderStates.A_REPOSITORY_EXISTS);
  }

  @State(ProviderStates.TWO_REPOSITORIES)
  Map<String, String> twoRepositories() {
    return states.params(ProviderStates.TWO_REPOSITORIES);
  }

  @State(ProviderStates.A_REPOSITORY_WITH_FILES_ON_MAIN)
  Map<String, String> aRepositoryWithFilesOnMain() {
    return states.params(ProviderStates.A_REPOSITORY_WITH_FILES_ON_MAIN);
  }

  @State(ProviderStates.A_REPOSITORY_MISSING_THE_REQUESTED_PATH)
  Map<String, String> aRepositoryMissingTheRequestedPath() {
    return states.params(ProviderStates.A_REPOSITORY_MISSING_THE_REQUESTED_PATH);
  }

  @State(ProviderStates.A_REPOSITORY_MISSING_THE_REQUESTED_COMMIT)
  Map<String, String> aRepositoryMissingTheRequestedCommit() {
    return states.params(ProviderStates.A_REPOSITORY_MISSING_THE_REQUESTED_COMMIT);
  }

  @State(ProviderStates.A_WRAPPER_REPOSITORY_WITH_A_SUBMODULE)
  Map<String, String> aWrapperRepositoryWithASubmodule() {
    return states.params(ProviderStates.A_WRAPPER_REPOSITORY_WITH_A_SUBMODULE);
  }

  @State(ProviderStates.A_REPOSITORY_WHOSE_MAIN_CONTAINS_A_COMMIT)
  Map<String, String> aRepositoryWhoseMainContainsACommit() {
    return states.params(ProviderStates.A_REPOSITORY_WHOSE_MAIN_CONTAINS_A_COMMIT);
  }

  @State(ProviderStates.A_REPOSITORY_WITH_A_SIDE_BRANCH)
  Map<String, String> aRepositoryWithASideBranch() {
    return states.params(ProviderStates.A_REPOSITORY_WITH_A_SIDE_BRANCH);
  }

  @State(ProviderStates.A_REPOSITORY_WITHOUT_THE_GIVEN_BRANCH)
  Map<String, String> aRepositoryWithoutTheGivenBranch() {
    return states.params(ProviderStates.A_REPOSITORY_WITHOUT_THE_GIVEN_BRANCH);
  }

  @State(ProviderStates.A_REPOSITORY_WITH_A_BRANCH_TO_FOLD)
  Map<String, String> aRepositoryWithABranchToFold() {
    return states.params(ProviderStates.A_REPOSITORY_WITH_A_BRANCH_TO_FOLD);
  }

  @State(ProviderStates.A_REPOSITORY_WITH_A_BRANCH_THAT_CONFLICTS_WITH_MAIN)
  Map<String, String> aRepositoryWithABranchThatConflictsWithMain() {
    return states.params(ProviderStates.A_REPOSITORY_WITH_A_BRANCH_THAT_CONFLICTS_WITH_MAIN);
  }

  @State(ProviderStates.A_REPOSITORY_WITHOUT_THE_BRANCH_TO_FOLD)
  Map<String, String> aRepositoryWithoutTheBranchToFold() {
    return states.params(ProviderStates.A_REPOSITORY_WITHOUT_THE_BRANCH_TO_FOLD);
  }

  @State(ProviderStates.A_REPOSITORY_WITH_THE_RELEASE_TAG)
  Map<String, String> aRepositoryWithTheReleaseTag() {
    return states.params(ProviderStates.A_REPOSITORY_WITH_THE_RELEASE_TAG);
  }

  @State(ProviderStates.A_REPOSITORY_DECLARING_ITS_DEPLOYMENTS)
  Map<String, String> aRepositoryDeclaringItsDeployments() {
    return states.params(ProviderStates.A_REPOSITORY_DECLARING_ITS_DEPLOYMENTS);
  }

  @State(ProviderStates.A_REPOSITORY_DECLARING_NO_DEPLOYMENTS)
  Map<String, String> aRepositoryDeclaringNoDeployments() {
    return states.params(ProviderStates.A_REPOSITORY_DECLARING_NO_DEPLOYMENTS);
  }

  @State(ProviderStates.AN_UNNAMED_REPOSITORY_DECLARING_ITS_DEPLOYMENTS)
  Map<String, String> anUnnamedRepositoryDeclaringItsDeployments() {
    return states.params(ProviderStates.AN_UNNAMED_REPOSITORY_DECLARING_ITS_DEPLOYMENTS);
  }

  @State(ProviderStates.A_REPOSITORY_DECLARING_ITS_CONFIGURATION)
  Map<String, String> aRepositoryDeclaringItsConfiguration() {
    return states.params(ProviderStates.A_REPOSITORY_DECLARING_ITS_CONFIGURATION);
  }

  @State(ProviderStates.A_REPOSITORY_DECLARING_NO_CONFIGURATION)
  Map<String, String> aRepositoryDeclaringNoConfiguration() {
    return states.params(ProviderStates.A_REPOSITORY_DECLARING_NO_CONFIGURATION);
  }

  @State(ProviderStates.A_REPOSITORY_DECLARING_AN_EVENT_TRIGGER)
  Map<String, String> aRepositoryDeclaringAnEventTrigger() {
    return states.params(ProviderStates.A_REPOSITORY_DECLARING_AN_EVENT_TRIGGER);
  }

  @State(ProviderStates.A_REPOSITORY_WITHOUT_A_CONFIG_DIRECTORY)
  Map<String, String> aRepositoryWithoutAConfigDirectory() {
    return states.params(ProviderStates.A_REPOSITORY_WITHOUT_A_CONFIG_DIRECTORY);
  }

  @State(ProviderStates.A_REPOSITORY_WITH_AN_IMAGE_ON_MAIN)
  Map<String, String> aRepositoryWithAnImageOnMain() {
    return states.params(ProviderStates.A_REPOSITORY_WITH_AN_IMAGE_ON_MAIN);
  }
}
