-- V49: Profile isolation — persist session cwd/git_repo_root for project/repo/worktree grouping.
-- Hermes records the working directory (and repo root when inside a git work
-- tree) on every session; the dashboard project tree groups by project → repo
-- → cwd. Both columns are nullable: legacy rows and sessions created outside
-- a work tree keep NULL and stay in the "Home / no project" bucket.

ALTER TABLE sessions ADD COLUMN IF NOT EXISTS cwd TEXT;
ALTER TABLE sessions ADD COLUMN IF NOT EXISTS git_repo_root TEXT;

CREATE INDEX IF NOT EXISTS idx_sessions_cwd ON sessions(cwd);
CREATE INDEX IF NOT EXISTS idx_sessions_git_repo_root ON sessions(git_repo_root);
