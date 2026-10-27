package eu.wohlben.qits.githost.storage;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import org.eclipse.jgit.attributes.AttributesNode;
import org.eclipse.jgit.attributes.AttributesNodeProvider;
import org.eclipse.jgit.internal.storage.dfs.DfsRepository;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.RefDatabase;
import org.eclipse.jgit.lib.RefUpdate;

/**
 * A git repository stored in a blob store and a catalog, with no directory anywhere.
 *
 * <p>It is a {@code Repository}, so {@code UploadPack} and {@code ReceivePack} serve it unchanged —
 * clone, fetch, push, push options and hooks all behave as they do over a bare on a disk. Build one
 * with {@link QitsDfsRepositoryBuilder}.
 *
 * <p>Two things it is <b>not</b>, both of which have caught someone already:
 *
 * <ul>
 *   <li><b>The git CLI cannot open it.</b> There is no directory to point {@code --git-dir} at, no
 *       worktree to add, no config file to write. Every operation on a repository stored this way is
 *       either the wire protocol or in-process JGit; there is no third door. That property is the
 *       point — the only writers are receive-pack and the host's own in-process code, so nothing
 *       changes a ref behind the host's back and every move can be announced.
 *   <li><b>{@link #getConfig()} does not persist.</b> {@code DfsRepository} answers with a {@code
 *       DfsConfig}, whose load and save are no-ops, so a per-repository setting written there is
 *       forgotten immediately and read back as the platform default. Anything that was a line in a
 *       bare's {@code config} needs a row somewhere else.
 * </ul>
 *
 * <p><b>Its {@code info/attributes} is a value, not a file</b> (qits-1691). A bare on a disk reads
 * {@code $GIT_DIR/info/attributes} and every in-process merge honours it; {@code DfsRepository}
 * inherits an attributes provider that answers nothing, so an in-core {@code ResolveMerger} saw only
 * the attributes committed in the trees it merges. The rule text handed to {@link
 * QitsDfsRepositoryBuilder#setInfoAttributes(String)} is that file's replacement — the platform's
 * {@code memory/MEMORY.md merge=union}, which turns two branches that each append a line to the
 * memory index from a guaranteed fold conflict into a merge that keeps both lines.
 */
public class QitsDfsRepository extends DfsRepository {

  private final String repositoryId;
  private final QitsDfsObjDatabase objects;
  private final QitsDfsReftableDatabase refs;

  /**
   * The repository-wide attribute rules, parsed once, or {@code null} when none were given. Null is
   * JGit's own spelling of "no {@code info/attributes} file" and is what the inherited provider
   * answered before qits-1691, so a repository built without the rule behaves byte for byte as one
   * always did.
   */
  private final AttributesNode infoAttributes;

  QitsDfsRepository(QitsDfsRepositoryBuilder builder) {
    super(builder);
    this.repositoryId = builder.getRepositoryId();
    this.objects =
        new QitsDfsObjDatabase(
            this, builder.getPackBlobStore(), builder.getPackCatalog(), builder.getReaderOptions());
    // Constructed once, here. A getRefDatabase() that returns a new instance per call compiles,
    // serves an advertisement, and then reads refs wrong — the ref cache is per instance.
    this.refs = new QitsDfsReftableDatabase(this);
    this.infoAttributes = parseAttributes(builder.getInfoAttributes());
  }

  /** {@code null} for no text or blank text; otherwise the rules, in gitattributes syntax. */
  private static AttributesNode parseAttributes(String text) {
    if (text == null || text.isBlank()) {
      return null;
    }
    AttributesNode node = new AttributesNode();
    try {
      node.parse(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)));
    } catch (IOException e) {
      // A byte array does not fail to read; the checked exception is the stream's signature only.
      throw new UncheckedIOException("cannot parse the info attributes", e);
    }
    return node;
  }

  /**
   * The {@code info/attributes} a disk bare would have read, answered from the builder's rule text
   * (qits-1691). There is <b>no global node</b>: a {@code core.attributesFile} lives in a user's
   * home, and a host serving every repository on the platform has no user whose home should decide
   * how a fold merges.
   *
   * <p>A new provider per call is what {@code Repository} expects of this factory method; both
   * answers are the one node parsed at construction, which nothing mutates after {@code parse}.
   */
  @Override
  public AttributesNodeProvider createAttributesNodeProvider() {
    return new AttributesNodeProvider() {
      @Override
      public AttributesNode getInfoAttributesNode() {
        return infoAttributes;
      }

      @Override
      public AttributesNode getGlobalAttributesNode() {
        return null;
      }
    };
  }

  /** The key both ports address this repository by. */
  public String getRepositoryId() {
    return repositoryId;
  }

  @Override
  public QitsDfsObjDatabase getObjectDatabase() {
    return objects;
  }

  @Override
  public RefDatabase getRefDatabase() {
    return refs;
  }

  /**
   * Creates the repository with {@code HEAD} pointing at {@code defaultBranch}, which need not
   * exist yet — the shape a freshly provisioned origin has, and what makes the first push a create.
   *
   * <p>{@link #create(boolean)} does the same thing but hardcodes {@code master}, so this exists to
   * keep the platform's {@code main} out of a caller's hands.
   *
   * @throws IOException if the repository already has refs
   */
  public void create(String defaultBranch) throws IOException {
    if (exists()) {
      throw new IOException("git repository " + repositoryId + " already exists");
    }
    RefUpdate.Result result =
        updateRef(Constants.HEAD, true).link(Constants.R_HEADS + defaultBranch);
    if (result != RefUpdate.Result.NEW) {
      throw new IOException("could not point HEAD at " + defaultBranch + ": " + result.name());
    }
  }
}
