---
name: project-conventions
description: Repo-wide coding conventions enforced by pre-commit (copyright headers, scalafmt, Daml warts, TS formatting) so generated code matches on the first pass.
user-invocable: false
---

# splice project conventions

These are enforced by `.pre-commit-config.yaml` and CI; matching them up front avoids pre-commit failures and review churn.

## All languages
- Every source file (`.daml`, `.scala`, `.sh`, `.py`, `.tsx`, `.rst`) needs the SPDX copyright header (see `scripts/rename.sh`, `headerCreate` sbt task). Copy the header from a neighboring file in the same directory rather than inventing one.
- No trailing whitespace (`check-trailing-whitespace.sh`).

## Scala
- Format with scalafmt rules in `.scalafmt.conf`; don't hand-format against them.
- Follow `.scalafix.conf` rules where applicable.
- After adding, deleting or renaming a test suite under `**/test/**/*.scala`, run `sbt updateTestConfigForParallelRuns` and commit the `test*.log` files it changes. Static checks regenerate them and fail on any diff (`build.static_tests.yml`, "Verify no changes in SBT test files").

## Daml
- No illegal cross-package Daml references (`scripts/rename.sh no_illegal_daml_references`) — don't import across module boundaries that aren't part of the public API.
- Avoid Daml warts flagged by `scripts/check-daml-warts.sh` (e.g. unsafe patterns the repo has banned).
- All Daml return types must satisfy `scripts/check-daml-return-types.sh` — avoid inferred/implicit return types where the script expects explicit ones.
- Changing `daml.yaml` or `.daml` files requires the `dars_lock` file to stay in sync (`damlDarsLockFileUpdate`).
- Frontend packages depend on generated JS bindings pinned by version, e.g. `@daml.js/splice-wallet` in `apps/package.json` (`file:common/frontend/daml.js/splice-wallet-0.1.23`). Bumping a Daml package version means regenerating and re-pinning these — check `apps/package.json` and the relevant frontend `package.json` for stale versions after Daml model changes.

## TypeScript / frontend
- Format via `scripts/fix-ts.py`, not raw prettier — it wraps prettier with repo-specific behavior. Skip `*.prettierrc.cjs` files.
- Each frontend app (`ans`, `scan`, `splitwell`, `sv`, `validator`, `wallet`) under `apps/` has its own `openapi-ts-client` package generated from the app's OpenAPI spec — don't hand-edit generated client code; regenerate instead.
- ESLint runs with `--max-warnings=0` — warnings fail the build, not just errors.
- Workspaces are declared in `apps/package.json`; new frontend packages must be added there.

## Shell / CI / infra
- Shellcheck applies to all `*.sh` except under `canton/` and `.envrc` (`-e SC1091` globally ignored).
- Changes under `.github/**` are checked by `scripts/actionlint.sh`.
- Changes to `cluster/pulumi/**/*.ts` or any `cluster/**/*.{yaml,yml}` trigger `make cluster/pulumi/test` — verify Pulumi tests pass locally before proposing infra changes.
- Grafana dashboard JSON under `cluster/pulumi/observability/grafana-dashboards/` is validated by `scripts/check-grafana-dashboards.sh`.

## AI-assisted contributions
Per `AI_POLICY.md`: the human author is fully accountable for AI-assisted contributions and must be able to explain every line. Don't pad PRs or review comments with unreviewed AI output — self-review before handing anything to a human reviewer.
