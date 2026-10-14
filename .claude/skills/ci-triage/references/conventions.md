# Conventions (these override defaults)

- Refs and runs: record the user's (run, job, ref) tuples verbatim. Never infer refs by elimination.
- Pure ASCII in every file, commit message and PR text. No em dashes, smart quotes, arrows, emoji.
- No code comments in fixes; the reasoning goes in the packet and the commit subject.
- No AI attribution anywhere (no Co-Authored-By, no "generated with"). `Signed-off-by` is the only trailer.
- Commit messages: one subject line plus the DCO sign-off. CI tag mandatory: `[skip ci]` on the triage branch
  (never on a PR branch: it makes the PR unmergeable), `[ci]` on fix branches, `[static]` for lint-only.
- Never commit CLAUDE.md or memory files. The triage branch carries only `ci-triage/` (packets, README,
  HANDOVER, ONBOARDING, known-families.md). Nothing else: this skill lives on main, and changes to it go through a
  normal PR.
- Canton: `canton/` is vendored and stale. The binary is pinned in `nix/canton-sources.json`; runtime log lines
  stand on their own, source citations only against the jar of the version that ran.
- Log-ignore additions are a last resort and must argue why the line can never carry signal. A rejected
  precedent: the IndexerState reconnect WARN (10084) and the P2P auth-token WARN (10010).
- Root cause, not symptom: for a real failure, find what the stalled component was waiting on and why, with a
  timestamped event. Timeouts are never the cause.
- Fix branches: one branch per PR, stacked follow-ups on the same branch, never a branch per fix. Name them
  `<user>/fix-<ref>-<slug>` or `<user>/backport-<ref>-<pr>-<release-line>`, where `<user>` is your own short git
  prefix, so the ref is visible in `git branch` and PR titles. Test-only fixes are written; production changes
  are described and left to the owner unless asked.
- Do not run sbt compiles or start Canton unless asked; triage works from the run's logs and artifacts.
- Tracker: refs are issues in DACH-NY/cn-test-failures, which needs DACH-NY access. If you cannot read it, the
  (run, job, ref) mapping comes from the user.
- Artifacts: `log/<ref>/` in the repo (gitignored), with `TMPDIR` under `log/` too, since `gh run download`
  stages there and runs are large; keep `.clog.gz` compressed and stream with `zcat`.
