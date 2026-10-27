package eu.wohlben.qits.githost.storage;

import java.util.Objects;
import org.eclipse.jgit.internal.storage.dfs.DfsReaderOptions;
import org.eclipse.jgit.internal.storage.dfs.DfsRepositoryBuilder;
import org.eclipse.jgit.internal.storage.dfs.DfsRepositoryDescription;

/**
 * Builds a {@link QitsDfsRepository} from a repository id and the two ports.
 *
 * <p>Opening a repository is cheap and reads nothing: no blob is fetched and no catalog row is read
 * until something asks for an object or a ref. So the caller opens one per request and closes it,
 * the same way {@code GitHostRoutes} opens a bare today.
 *
 * <p>Repositories are addressed by id alone. The id is not a path — nothing here joins it to a
 * directory — so it needs no traversal defence of its own, though whatever hands it in should still
 * be sure of where it came from.
 */
public class QitsDfsRepositoryBuilder
    extends DfsRepositoryBuilder<QitsDfsRepositoryBuilder, QitsDfsRepository> {

  private String repositoryId;
  private PackBlobStore blobs;
  private PackCatalog catalog;
  private String infoAttributes;

  /** Also becomes the repository description, which keys JGit's block cache. */
  public QitsDfsRepositoryBuilder setRepositoryId(String repositoryId) {
    this.repositoryId = repositoryId;
    return setRepositoryDescription(new DfsRepositoryDescription(repositoryId));
  }

  public String getRepositoryId() {
    return repositoryId;
  }

  public QitsDfsRepositoryBuilder setPackBlobStore(PackBlobStore blobs) {
    this.blobs = blobs;
    return self();
  }

  public PackBlobStore getPackBlobStore() {
    return blobs;
  }

  public QitsDfsRepositoryBuilder setPackCatalog(PackCatalog catalog) {
    this.catalog = catalog;
    return self();
  }

  public PackCatalog getPackCatalog() {
    return catalog;
  }

  /**
   * The repository's {@code info/attributes}, as text in gitattributes syntax — one rule per line,
   * for example {@code memory/MEMORY.md merge=union} (qits-1691). {@code null} or blank means no
   * rules, which is what a repository built without this call has.
   *
   * <p>A value and not a lookup: this module has one compile dependency, JGit, and reads no
   * configuration of its own. Whoever opens the repository decides the text; in the service that is
   * {@code DfsGitRepositoryProvider}, from {@code qits.githost.merge.info-attributes}.
   */
  public QitsDfsRepositoryBuilder setInfoAttributes(String infoAttributes) {
    this.infoAttributes = infoAttributes;
    return self();
  }

  public String getInfoAttributes() {
    return infoAttributes;
  }

  @Override
  public QitsDfsRepository build() {
    Objects.requireNonNull(repositoryId, "repositoryId");
    Objects.requireNonNull(blobs, "packBlobStore");
    Objects.requireNonNull(catalog, "packCatalog");
    if (getReaderOptions() == null) {
      setReaderOptions(new DfsReaderOptions());
    }
    return new QitsDfsRepository(this);
  }
}
