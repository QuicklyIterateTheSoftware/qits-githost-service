package eu.wohlben.qits.githost;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.fail;

import au.com.dius.pact.consumer.ConsumerPactRunnerKt;
import au.com.dius.pact.consumer.PactVerificationResult;
import au.com.dius.pact.consumer.model.MockProviderConfig;
import au.com.dius.pact.core.model.PactSpecVersion;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

/**
 * <b>The consumer half of the qits-projects contract</b> (ticket qits-1149): the real {@link
 * HttpRepositoryNameResolver}, making a real HTTP call, against a pact-jvm mock server that answers
 * exactly what {@link ProjectsContract}'s row promises. Plain JUnit, as the resolver needs no CDI
 * to build.
 *
 * <p>One mock server per row, as in qits-maintenance-service: the rows send the same request and
 * differ only by trigger, which one shared server cannot tell apart.
 */
class ProjectsConsumerPactTest {

  static {
    // pact-jvm reports usage metrics over the network unless told not to; a test never should.
    System.setProperty("pact_do_not_track", "true");
  }

  @Test
  void everyRowIsWhatTheResolverAsksAndUnderstands() {
    assertFalse(ProjectsContract.CASES.isEmpty());
    run(ProjectsContract.CASES);
  }

  @Test
  @Disabled(ProjectsContract.PENDING_REASON)
  void aResolvedNameIsWhatTheResolverReads() {
    // When qits-projects records the state: move PENDING into CASES and update the pact file.
    run(ProjectsContract.PENDING);
  }

  private static void run(List<ProjectsContract.Case> rows) {
    List<String> failures = new ArrayList<>();
    for (ProjectsContract.Case row : rows) {
      PactVerificationResult result =
          ConsumerPactRunnerKt.runConsumerTest(
              ProjectsContract.pact(List.of(row)),
              MockProviderConfig.createDefault(PactSpecVersion.V4),
              (mockServer, context) -> {
                row.run()
                    .accept(
                        ProjectsContract.resolverAgainst(mockServer.getUrl()),
                        ProjectsContract.PROJECTS.params(row.state()));
                return null;
              });
      if (!(result instanceof PactVerificationResult.Ok)) {
        failures.add(row.description() + " [" + row.state() + "]: " + describe(result));
      }
    }
    if (!failures.isEmpty()) {
      fail(failures.size() + " contract row(s) failed:\n  " + String.join("\n  ", failures));
    }
  }

  private static String describe(PactVerificationResult result) {
    if (result instanceof PactVerificationResult.Error error) {
      return "error: " + error.getError();
    }
    return result.getDescription() + " — " + result;
  }
}
