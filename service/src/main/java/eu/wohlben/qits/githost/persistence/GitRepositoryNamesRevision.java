package eu.wohlben.qits.githost.persistence;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * Which statement of a repository's name set this host holds: the revision qits-projects stamped on
 * the last {@code PUT /git/:repoId/names} that was applied, and the project the set belongs to.
 *
 * <p>It orders the writes — a PUT whose revision is not newer than this one changes nothing — and it
 * is the row {@link RepositoryNameStore#apply} locks, so two PUTs for one repository serialise on it.
 * No row means no set was ever stated, which the door answers as revision {@code -1}.
 */
@Entity
@Table(name = "git_repository_names_revision")
public class GitRepositoryNamesRevision extends PanacheEntityBase {

  @Id
  @Column(name = "repository_id", length = 64)
  public String repositoryId;

  @Column(name = "project_id", length = 64, nullable = false)
  public String projectId;

  @Column(name = "revision", nullable = false)
  public long revision;

  @Column(name = "updated_at", nullable = false)
  public Instant updatedAt;
}
