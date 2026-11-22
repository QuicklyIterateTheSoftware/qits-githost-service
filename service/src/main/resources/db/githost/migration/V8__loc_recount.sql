-- The lines-of-code rules changed: a path beneath a directory segment that itself looks like a
-- *.spec/test.ts|tsx|js file (qits-2500) now counts as test too. A stored summary is a memo of the
-- old rules, so all of them go. Nothing is lost: the next push, browse or list call recounts a
-- commit and stores it again.
delete from git_repository_loc;
