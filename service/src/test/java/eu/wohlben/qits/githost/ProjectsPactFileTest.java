package eu.wohlben.qits.githost;

import eu.wohlben.qits.githost.contracts.PactFiles;
import org.junit.jupiter.api.Test;

/**
 * <b>The committed consumer pact, {@code pacts/qits-githost-service_qits-projects-service.json}</b>
 * (ticket qits-1149), modeled on qits-maintenance-service's class of the same name: what {@link
 * ProjectsContract} describes, compared by {@link PactFiles} with the committed file. The platform
 * packs {@code pacts/*_qits-projects-service.json} into this repository's pacts jar for qits-projects
 * (see {@code .config/qits/release.yml}).
 */
class ProjectsPactFileTest {

  static final String PROVIDER = "qits-projects-service";

  static final String FILE = PactFiles.file(PROVIDER);

  @Test
  void theCommittedPactIsWhatTheContractWrites() {
    PactFiles.compareOrWrite(FILE, ProjectsContract.pact());
  }

  /** Both references on every interaction, and status-only rows carry no body. */
  @Test
  void everyInteractionCarriesBothReferences() {
    PactFiles.assertWellFormed(ProjectsContract.pact(), PROVIDER, ProjectsContract.CASES.size());
  }
}
