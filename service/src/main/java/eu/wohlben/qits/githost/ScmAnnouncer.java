package eu.wohlben.qits.githost;

import java.util.Collection;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.transport.ReceiveCommand;
import org.jboss.logging.Logger;

/**
 * Port: something that is told about a ref move after it has landed. The seam between the doors
 * that write refs and the platform, and the replacement for the {@code PostReceiveNotifier} that
 * used to POST to two hard-coded urls.
 *
 * <p><b>Every door that moves a ref calls it</b>, so a consumer cannot tell which one did: a push
 * through {@link GitHostRoutes}, and each in-core write of {@code api.RepositoryRefsResource} — a
 * merge, a commit, a tag, a branch deletion — which hands over the one {@link ReceiveCommand} its
 * ref update amounts to, exactly as if a push had carried it. That is the platform's invariant:
 * every ref move is announced, whichever door moved it.
 *
 * <p>Two implementations ship — {@code bus.ScmEventAnnouncer}, which publishes the {@code
 * githost-events} vocabulary through {@code QitsEventBus}, and {@code loc.LocAnnouncer}, which warms
 * the lines-of-code memo — and <b>zero is a supported configuration</b>: a deployment with no
 * announcer serves git and announces nothing. The port stays a port because it is what keeps "there
 * is a bus" out of {@link GitHostRoutes}.
 *
 * <p><b>Three rules an implementation must keep</b>, all of them consequences of where it is
 * called:
 *
 * <ul>
 *   <li><b>Do not throw.</b> This runs inside {@code ReceivePack.receive} — or inside the door's
 *       request — after the ref updates have already been applied. The write has succeeded by
 *       then, so a failure here must never turn it into one. {@link #announceToEach} catches
 *       anything that escapes, and that is a backstop rather than a licence.
 *   <li><b>Do not block for long.</b> The response has not been written yet, so every millisecond
 *       spent here is latency the pusher or the calling service sees. Reading the repository is expected —
 *       that is what the {@code repo} argument is for — and waiting on a network is not.
 *   <li><b>Read the repository, do not write it.</b> {@code repo} is open and holds the objects the
 *       write just delivered; it is closed by the caller as soon as this returns, so nothing may
 *       hold on to it.
 * </ul>
 *
 * @see GitHostRoutes
 */
public interface ScmAnnouncer {

  /**
   * A push — or a door write shaped like one — landed on {@code repoId}.
   *
   * @param repoId the opaque storage id the push landed in
   * @param projectId the project segment of the address the push arrived on, or {@code null} when it
   *     arrived on the internal id-addressed scheme. <b>Echoed, never resolved</b>: this host looks
   *     no name up and holds none, it repeats what the pusher addressed.
   * @param repoName the repository segment of that same address, or {@code null} for the same reason
   * @param repo the repository, open and readable, including the objects this push delivered
   * @param commands every command of the push, <b>including the ones that failed</b> — an
   *     implementation filters on {@link ReceiveCommand#getResult()} itself, because a refused ref
   *     did not move and must not be announced
   */
  void onPostReceive(
      String repoId,
      String projectId,
      String repoName,
      Repository repo,
      Collection<ReceiveCommand> commands);

  /**
   * Tells each announcer about {@code commands}, one at a time, so one announcer cannot take a ref
   * move down with it or keep the next from hearing of it. Every caller has already moved the refs
   * by the time it gets here; a write that succeeded must be reported as a success even if nobody
   * could be told about it, so a failure is logged at WARN and swallowed.
   *
   * <p>The one place that catch is written, shared by the push route and the REST doors.
   */
  static void announceToEach(
      Iterable<? extends ScmAnnouncer> announcers,
      String repoId,
      String projectId,
      String repoName,
      Repository repo,
      Collection<ReceiveCommand> commands) {
    for (ScmAnnouncer announcer : announcers) {
      try {
        announcer.onPostReceive(repoId, projectId, repoName, repo, commands);
      } catch (Exception e) {
        Logger.getLogger(ScmAnnouncer.class)
            .warnf(e, "post-receive announcement for %s failed", repoId);
      }
    }
  }
}
