-- The lines-of-code rules changed: lockfiles and paths marked linguist-generated or
-- linguist-vendored are no longer counted, golden-masters/ and pacts/ count as test, and every
-- language carries a category. A stored summary is a memo of the old rules, so all of them go.
-- Nothing is lost: the next push, browse or list call recounts a commit and stores it again.
delete from git_repository_loc;
