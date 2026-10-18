package eu.wohlben.qits.githost.persistence;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

/**
 * One project-scoped name and the repository it resolves to — the row {@code
 * /git/:projectId/:repoName} is answered from.
 *
 * <p>qits-projects owns the names and is the one writer, through {@code PUT /git/:repoId/names};
 * {@link RepositoryNameStore} replaces a repository's whole set per call. The key is {@code
 * (projectId, name)}, so a name belongs to exactly one repository of a project at a time, and a set
 * that states a name another repository holds moves it. Case-sensitive, as the url segment is.
 */
@Entity
@Table(name = "git_repository_name")
@IdClass(GitRepositoryNameId.class)
public class GitRepositoryName extends PanacheEntityBase {

  @Id
  @Column(name = "project_id", length = 64)
  public String projectId;

  @Id
  @Column(name = "name")
  public String name;

  @Column(name = "repository_id", length = 64, nullable = false)
  public String repositoryId;
}
