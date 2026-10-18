package eu.wohlben.qits.githost.persistence;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * A project's slug — the second spelling of the first url segment, so {@code /git/qits/<name>}
 * resolves as {@code /git/<projectId>/<name>} does.
 *
 * <p>Written alongside a repository's names by {@link RepositoryNameStore#apply}. A slug is held by
 * one project at a time (the unique constraint), and stating it for another project moves it. Rows
 * outlive the repositories that stated them: the slug describes the project.
 */
@Entity
@Table(name = "git_project")
public class GitProject extends PanacheEntityBase {

  @Id
  @Column(name = "project_id", length = 64)
  public String projectId;

  @Column(name = "slug", nullable = false, unique = true)
  public String slug;
}
