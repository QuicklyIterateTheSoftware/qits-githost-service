package eu.wohlben.qits.githost;

import jakarta.enterprise.context.ApplicationScoped;
import java.util.Collection;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.transport.ReceiveCommand;

/**
 * An {@link ScmAnnouncer} that breaks the port's first rule on demand: armed, it throws from {@code
 * onPostReceive}. Disarmed — the default, and the state every other test sees — it does nothing.
 *
 * <p>It exists to prove the backstop in {@link ScmAnnouncer#announceToEach}: a ref move that has
 * already landed is answered as a success, and the announcers beside the broken one are still told.
 * A test that arms it disarms it in a {@code finally}, because the bean is application-scoped and
 * shared by every test of the same Quarkus application.
 */
@ApplicationScoped
public class FailingAnnouncer implements ScmAnnouncer {

  private volatile boolean armed;

  public void arm() {
    armed = true;
  }

  public void disarm() {
    armed = false;
  }

  @Override
  public void onPostReceive(
      String repoId,
      String projectId,
      String repoName,
      Repository repo,
      Collection<ReceiveCommand> commands) {
    if (armed) {
      throw new IllegalStateException("an announcer that broke the port's rule, on purpose");
    }
  }
}
