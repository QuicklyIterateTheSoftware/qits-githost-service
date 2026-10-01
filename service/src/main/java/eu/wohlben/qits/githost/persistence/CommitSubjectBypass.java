package eu.wohlben.qits.githost.persistence;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * One recorded use of {@code -o qits.subject-bypass=<reason>}: a push the commit-subject guard
 * would have refused and let through because the pusher said why. Append-only; see {@code
 * V4__commit_subject_bypass.sql}.
 */
@Entity
@Table(name = "commit_subject_bypass")
public class CommitSubjectBypass extends PanacheEntityBase {

  @Id
  @Column(name = "id")
  public UUID id;

  @Column(name = "repository_id", nullable = false)
  public String repositoryId;

  /** The pushing identity's name: a token's subject or client id. */
  @Column(name = "pusher", nullable = false)
  public String pusher;

  @Column(name = "reason", nullable = false)
  public String reason;

  /** The refs the push named, newline-separated. */
  @Column(name = "refs", nullable = false)
  public String refs;

  /** The full shas of the commits the guard would have refused, newline-separated. */
  @Column(name = "commits", nullable = false)
  public String commits;

  @Column(name = "used_at", nullable = false)
  public Instant usedAt;
}
