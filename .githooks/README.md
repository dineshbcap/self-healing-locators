# Git hooks

Activate once per clone:

```bash
git config core.hooksPath .githooks
```

## prepare-commit-msg

Drafts a commit message from the staged diff via the Claude Code CLI
(`claude -p`) whenever you run `git commit` without `-m`/`-F`. Skips merges,
squashes, commits that already have a message, and silently does nothing
(never blocks the commit) if `claude` isn't installed/authenticated or the
call times out after 25s.
