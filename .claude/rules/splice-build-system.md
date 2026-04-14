# Splice Build System

## SBT Structure

### Key Build Files
- `build.sbt` (~2,300 lines) — root project aggregating 68+ subprojects
- `project/BuildCommon.scala` — shared settings, custom tasks, command aliases
- `project/Dependencies.scala` — Splice library versions (Scala 2.13.16, ScalaTest 3.2.19)
- `project/CantonDependencies.scala` — Canton/Daml SDK versions, Pekko 1.2.1, Slick 3.5.2
- `project/DamlPlugin.scala` (~587 lines) — custom AutoPlugin for DAML compilation
- `project/plugins.sbt` — WartRemover, Scalafmt, ScalaFix, Guardrail (OpenAPI), ScalaPB

### Subproject Categories
- **DAML packages** (36): `splice-amulet-daml`, `splice-wallet-daml`, etc.
- **Scala backends** (7): `apps-common`, `apps-sv`, `apps-validator`, `apps-scan`, `apps-wallet`, `apps-splitwell`, `apps-common-sv`
- **Frontends** (6): `apps-wallet-frontend`, `apps-scan-frontend`, etc.
- **Infrastructure**: `pulumi`, `load-tester`, `token-standard-cli`, `party-allocator`
- **Canton** (vendored): `canton-community-*`, `canton-ledger-*`

### Custom SBT Tasks
```bash
damlBuild              # Compile .daml → .dar files
damlTest               # Run DAML script tests
damlGenerateCode       # Java codegen from DARs
damlDarsLockFileUpdate # Update daml/dars.lock
updateDarResources     # Regenerate DarResources.scala
bundle                 # Create splice-node.tar.gz release
updateTestConfigForParallelRuns  # Update test-*.log parallelization files
```

### Command Aliases
```bash
format     # scalafmt ; Test/scalafmt ; scalafmtSbt
formatFix  # format + scalafixAll + apps-frontends/npmFix + headerCreate
lint       # Full lint check (formats, scalafix, npm lint, copyright, shellcheck)
clean-splice  # Clean Splice modules only (not Canton)
```

## DAR (DAML Archive) Management

### Build Output
- `.daml/dist/splice-{name}-{version}.dar` — versioned output
- `.daml/dist/splice-{name}-current.dar` — symlink to latest
- `daml/dars/*.dar` — committed DARs for stable package IDs
- `daml/dars.lock` — package ID lock file (CI-enforced)

### DarResources Generator
- Input: built DARs
- Output: `apps/common/src/main/scala/.../DarResources.scala` (auto-generated)
- Contains: `packageResources`, `pkgIdToDarResource`, `pkgMetadataToDarResource`
- Task: `sbt updateDarResources`

### DAR Change Workflow
1. Edit `.daml` files
2. Bump version in `daml.yaml` + recursive dependents
3. `sbt damlBuild; sbt damlDarsLockFileUpdate`
4. Update versions in `DarResources.scala` manually
5. `sbt updateDarResources`
6. Update `apps/package.json` DAML package versions
7. `sbt npmInstall; sbt compile`

## Nix / direnv

### Dev Environment
- `nix/flake.nix` — defines dev shells: `default` (enterprise), `oss`, `static_tests`
- `nix/shell.nix` — 114+ packages (SBT, Scala, openjdk21, K8s tools, Python3, Pulumi)
- `nix/canton-sources.json` — Canton version + Docker image SHA digests
- `nix/daml-compiler-sources.json` — DAML compiler version

### Environment Variables (from direnv)
- `CANTON` — path to Canton binary
- `CANTON_VERSION` — from canton-sources.json
- `DAML_COMPILER_VERSION` — from daml-compiler-sources.json
- Private vars in `.envrc.private` (gitignored): `ARTIFACTORY_USER`, `ARTIFACTORY_PASSWORD`, `GH_USER`, `GH_TOKEN`, Auth0 credentials

## Code Formatting

### Scalafmt
- Config: `.scalafmt.conf` (version 3.7.1)
- Max column: 100
- Trailing commas: `multiple` (diff-friendly)
- Scala 3 syntax conversion enabled (except `.sbt` and `project/`)

### Scalafix
- Semantic analysis for automated refactoring
- Run: `sbt scalafixAll`

### TypeScript
- Prettier via `scripts/fix-ts.py`
- ESLint per workspace

### Copyright Headers
- Apache-2.0 SPDX on all source files
- Enforced: `sbt headerCreate` / `sbt headerCheck`

## Pre-commit Hooks

`.pre-commit-config.yaml` defines:
1. `copyright` — SBT header validation
2. `scalafmt` — Scala formatting
3. `sbt_tests` — update test parallelization config on test file changes
4. `dars_lock` — update DAR lock on `.daml`/`daml.yaml` changes
5. `no_illegal_daml_references` — DAML reference validation
6. `shellcheck` — shell script linting
7. `typescriptfmt` — TypeScript/JS formatting
8. `pulumi_tests` — Pulumi config validation
9. `gha_lint` — GitHub Actions YAML linting
10. `check-trailing-whitespace`
11. `check-daml-warts` — DAML code smell detection
12. `check-daml-return-types` — return type validation
13. `check-grafana-dashboards` — dashboard JSON validation
14. `rstcheck` — Sphinx RST doc validation

## CI (GitHub Actions)

### Main Orchestrator: `.github/workflows/build.yml`
- Opt-in via `[ci]` commit tag (default: cancelled)
- Jobs: `static_tests`, `deployment_test`, `daml_test`, `scala_test_*` (9+ parallel suites), `typescript_tests`, `ui_tests`, `build_container`
- Self-hosted K8s runners: small/medium/x-large

### Static Tests: `.github/workflows/build.static_tests.yml`
- Canton consistency, scalafmt, scalafix, prettier, DAML warts/return types, TODO validation, shellcheck, actionlint, RST docs

### Scala Test Template: `.github/workflows/build.scala_test.yml`
- Reusable template with parallelism, PostgreSQL service, GCP workload identity
- Test categories tracked in `test-full-class-names-*.log` files

### Test Category Files
`test-full-class-names-sim-time.log`, `test-full-class-names-resource-intensive.log`, `test-full-class-names-disaster-recovery.log`, etc.
- Updated by `sbt updateTestConfigForParallelRuns` when adding new tests
- Must be committed with test additions

## Version Management

### Version File: `/VERSION` (currently `0.6.0`)
### Snapshot Format: `{base}-{YYYYMMDD}.{commit_count}.{commit_sha_8}`
### Release Detection: `[release]` commit tag or `release/*` branch

## Docker Images

### Registry: `ghcr.io/digital-asset/decentralized-canton-sync/docker`
### Frontend Images: Multi-stage (app build → nginx:1.29.0 unprivileged)
### Base Image Pinning: All base images pinned by SHA256 digest
### Config Injection: Runtime `envsubst` generates `config.js` → `window.splice_config`

## Dependency Bumping

### Canton Bump (significant effort)
See `MAINTENANCE.md` — involves patching our fork, reapplying changes, version alignment.
Key files: `nix/canton-sources.json`, `project/CantonDependencies.scala`

### DAML Compiler Bump
Update `nix/daml-compiler-sources.json` — changes all package IDs (requires governance vote).

### CometBFT Bump
Update `nix/cometbft-driver-sources.json`, vendor new proto JAR.
