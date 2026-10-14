---
name: ci-triage
description: Triage a failed splice GitHub Actions job (cn-test-failures ref) into a reproducible evidence packet - fetch job log and artifact, isolate the flagged lines, check the known flake families for duplicates, find the root cause with commands and verbatim log output, record it in the shared triage branch and, when a small test-side fix exists, put it on a branch. Use for "triage GHA run ... my ref ...", "is this a dup", "root cause of shard failure", checkErrors WARN/ERROR failures, integration test flakes.
---

# CI failure triage for splice

Input: one or more `(GitHub run URL or id, job name, cn-test-failures ref)` tuples from the user. A ref is an
issue number in the failure tracker `DACH-NY/cn-test-failures`, readable with a GitHub login that has DACH-NY
access. Output: one evidence packet per ref in `ci-triage/`, a row in `ci-triage/README.md`, a duplicate verdict,
and optionally a fix branch. Everything you write must be reproducible by someone else: every claim is a command
plus its verbatim output. Read `references/conventions.md` once before starting; it overrides defaults.

The skill lives on main; the triage data does not. Packets, `ci-triage/README.md`, `ci-triage/HANDOVER.md` and the
flake family catalogue `ci-triage/known-families.md` live on the shared triage branch, which is never merged. If it
is not checked out, ask the user which triage branch is current before writing anything.

## Procedure

1. Record the tuple verbatim. Never infer which ref belongs to which job by elimination. If a run has more
   failed jobs than refs, list all failed jobs (`gh run view <run> --json jobs`) and leave the mapping open until
   the user states it. If you cannot read the tracker, ask for the run URL rather than guess.
2. Fetch the job log by job id (`references/recipes.md` section 1). Classify in one pass:
   - `Tests: succeeded N, failed 0` + `contains problems` = checkErrors failure: the only evidence is the
     flagged log lines. The console masks `{}` as `***` and prints every ignored line with the suffix
     `(ignore this line in check-sbt-output.sh)`; the real problems are the `@timestamp` lines WITHOUT it.
   - `*** FAILED ***` = assertion or command failure: take the failing clue and stack head.
   - no report, job cancelled or exit without summary = evidence loss (JVM exit, timeout); say so.
3. Duplicate check BEFORE deep analysis: compare the flagged line and the shard's suite list against
   `ci-triage/known-families.md` on the triage branch. A match still needs the confirming grep listed there (for example the
   multi-host clue 1-15 s before an ACS mismatch period). Say "duplicate of X" only after that grep.
4. Establish what runs: canton pin from `nix/canton-sources.json` at the run's sha, never from `canton/`.
   For Canton behaviour cite the jar (`references/recipes.md` section 6), and state the version next to every
   source citation.
5. Download the artifact gzipped to `log/<ref>/<artifact>/` (gitignored), stream with `zcat`.
   Build the suite timeline from `canton_network_test.clog.gz`, then correlate flagged timestamps to it and
   to the canton log. Chase "what is it waiting on, and why" until the cause is a concrete event with a
   timestamp; do not stop at the timeout symptom.
6. Decide flake versus real, and where the fix belongs (test, splice app, Canton, infra). For a candidate
   fix that already exists on main, check `git merge-base --is-ancestor <sha> origin/<branch>`: a missing
   backport is the most common resolution for release-line failures.
7. Write the packet from `references/packet-template.md`: command, then verbatim output, per finding.
   Add the README row (mapping table, overview table, cross-cutting notes when a pattern spans refs).
   Add new families or occurrences to `ci-triage/known-families.md`.
8. Fix, only when it is test-side and small enough to review in one screen: one branch per PR off
   `origin/main` (or the release line for a backport, `cherry-pick -x -s`), in its own worktree so the triage
   branch stays checked out (`references/recipes.md` section 8), named
   `<user>/fix-<ref>-<slug>` (backports: `<user>/backport-<ref>-<pr>-<release-line>`), where `<user>` is your own
   short git prefix, so the branch carries the cn-test-failures ref; when one branch fixes several refs, use the
   parent ref and list the others in the README row. Single-subject commit with a CI tag and DCO sign-off, no
   code comments, no AI attribution. State plainly what was and was not verified.
   Do not compile or start Canton unless asked; cheap checks only (`scalafmtCheck`, `prettier --check`,
   config greps).
9. Commit the packet on the triage branch with `[skip ci]` and `-s`. Report: one line per ref with
   duplicate verdict and fix state, then the branch tips.

## Anti-patterns

- Proposing an ignore pattern for a WARN whose cause is not understood. Ignore patterns hide the only signal on
  shards where the underlying bug does not wedge (see 10010 in the catalogue).
- Citing `canton/` source line numbers: it is a stale rsync copy.
- Treating the suite list of a checkErrors shard as the culprit: the WARN's suite is the one running at the
  flagged timestamp, and commitment or ack WARNs often land minutes after the causing step.
- Pasting only conclusions. A packet without the commands that produced them is not accepted.
