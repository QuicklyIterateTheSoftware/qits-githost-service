-- Every use of the commit-subject break-glass that actually broke something.
--
-- CommitSubjectHook refuses a push carrying a new commit whose subject is not
-- `term(<project>-<n>): message`, on a repository whose default branch opts in with
-- `.config/qits/commit-subjects.yml`. `git push -o qits.subject-bypass="<why>"` lets such a push
-- through anyway, and a row is written here ONLY when the guard would have refused — a bypass
-- option on a push that already complies changes nothing and records nothing.
--
-- AN AUDIT TRAIL, append-only: a row is never updated, and deleting a repository leaves its rows
-- behind on purpose — "who pushed past the guard, and why" is a question that outlives the bytes.
-- Read newest first through GET /githost/api/repositories/{repoId}/commit-subject-bypasses.
--
-- Nothing here is a foreign key, per this schema's rule: repository_id is the same opaque string
-- the pack tables hold. `refs` and `commits` are newline-separated lists — the refs the push named,
-- and the full shas of the commits the guard would have refused (or a note that the walk hit its
-- cap) — kept as text because nothing queries into them.
create table commit_subject_bypass (
    id            uuid         not null primary key,
    repository_id varchar(255) not null,
    pusher        varchar(255) not null,
    reason        text         not null,
    refs          text         not null,
    commits       text         not null,
    used_at       timestamptz  not null
);

create index commit_subject_bypass_repository_used_at
    on commit_subject_bypass (repository_id, used_at desc);
