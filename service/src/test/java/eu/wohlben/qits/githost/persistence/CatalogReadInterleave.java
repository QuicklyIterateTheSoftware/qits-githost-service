package eu.wohlben.qits.githost.persistence;

import io.quarkus.hibernate.orm.PersistenceUnitExtension;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;
import org.hibernate.resource.jdbc.spi.StatementInspector;

/**
 * A test-only seam that runs a piece of work <b>on another transaction, in the middle of a catalog
 * read</b>: at the moment the reading thread prepares the first statement that touches {@code
 * git_pack_file}, before that statement executes.
 *
 * <p>That position is chosen to straddle both shapes the read has had (qits-1823). The old {@code
 * CatalogRepository.read} issued two SELECTs, {@code git_pack} and then {@code git_pack_file}, so the
 * hook fired <i>between</i> them: the packs already read from before the interleaved commit, the
 * files about to be read from after it — exactly the window the live race fell into. The fixed read
 * is one statement over both tables, so the hook fires before it and the whole read lands after the
 * commit. Nothing in the fixed shape is left between two statements to cut into, which is the point:
 * a test written against this seam fails on the old read and passes on the new one, without knowing
 * which it is looking at.
 *
 * <p>A Hibernate {@link StatementInspector}, because the inspector is called when a statement is
 * prepared and before it runs — the one hook that sits between statements without a production
 * seam for it. It is a no-op until {@link #arm armed}, fires <b>once</b>, and only on the thread that
 * armed it, so the interleaved work's own statements and every other test class's pass straight
 * through.
 */
@PersistenceUnitExtension("githost")
public class CatalogReadInterleave implements StatementInspector {

  private record Armed(Thread reader, Runnable work) {}

  private static final AtomicReference<Armed> ARMED = new AtomicReference<>();

  /** Runs {@code work} once, when the calling thread next prepares a {@code git_pack_file} read. */
  static void arm(Runnable work) {
    ARMED.set(new Armed(Thread.currentThread(), work));
  }

  /** Whether the armed work is still waiting — false once it has run, or if it never was armed. */
  static boolean pending() {
    return ARMED.get() != null;
  }

  static void disarm() {
    ARMED.set(null);
  }

  @Override
  public String inspect(String sql) {
    Armed armed = ARMED.get();
    if (armed != null
        && armed.reader() == Thread.currentThread()
        && sql.toLowerCase(Locale.ROOT).startsWith("select")
        && sql.contains("git_pack_file")
        && ARMED.compareAndSet(armed, null)) {
      armed.work().run();
    }
    return sql;
  }
}
