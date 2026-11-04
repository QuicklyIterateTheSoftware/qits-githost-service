-- The commit-subject break-glass is gone, and its audit trail with it (qits-706).
--
-- V4 created this table for `git push -o qits.subject-bypass="<why>"`, which let a refused push
-- through on a repository that had opted in with `.config/qits/commit-subjects.yml`. Both are
-- deleted: every repository now enforces one commit-message pattern (CommitMessageVariants), and
-- nothing lets a non-matching commit through, so there is no use left to record and no door that
-- reads one. V4 stays as it is, because a migration that ran is history.
drop table commit_subject_bypass;
