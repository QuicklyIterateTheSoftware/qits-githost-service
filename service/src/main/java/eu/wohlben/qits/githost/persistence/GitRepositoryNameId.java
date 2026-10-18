package eu.wohlben.qits.githost.persistence;

import java.io.Serializable;
import java.util.Objects;

/** Composite key of {@link GitRepositoryName}: a project and a name within it. */
public class GitRepositoryNameId implements Serializable {

  public String projectId;
  public String name;

  public GitRepositoryNameId() {}

  public GitRepositoryNameId(String projectId, String name) {
    this.projectId = projectId;
    this.name = name;
  }

  @Override
  public boolean equals(Object other) {
    return other instanceof GitRepositoryNameId id
        && Objects.equals(projectId, id.projectId)
        && Objects.equals(name, id.name);
  }

  @Override
  public int hashCode() {
    return Objects.hash(projectId, name);
  }
}
