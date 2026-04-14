# Splice Project Overview

## What Is Splice

Splice is a set of reference applications for operating, funding, and governing decentralized Canton synchronizers. It implements the Amulet token system for paying synchronizer fees and incentivizing network participation.

**Repository:** `hyperledger-labs/splice` (Apache 2.0)
**Languages:** Scala 2.13 (~12,700 files), DAML (~690 files), TypeScript/React (~40K files)
**Build:** SBT + Nix/direnv + npm workspaces
**Version:** Tracked in `/VERSION` (currently 0.6.0)

## Domain Glossary

| Term | Definition |
|------|-----------|
| **Amulet** | Utility token (Canton Coin) for paying synchronizer fees and distributing rewards |
| **DSO** | Decentralized Synchronizer Operator — governance entity requiring 2/3 BFT consensus from SVs |
| **DSO Party** | Code construct accumulating signatures from and taking actions on behalf of all active SVs |
| **SV (Super Validator)** | Independent organization operating synchronizer infrastructure (sequencer, mediator) |
| **Validator** | Participant node operator that validates transactions and earns rewards |
| **ANS** | Amulet Name Service — DNS-like directory for party resolution (handles like `alice.unverified.ans`) |
| **Mining Round** | Periodic issuance cycle: Open → Summarizing → Issuing → Closed |
| **Featured App** | Application provider earning extra rewards via `FeaturedAppRight` |
| **Transfer Preapproval** | Pre-authorization allowing receivers to accept transfers without sender being online |
| **Synchronizer** | Canton ordering/validation service — guarantees message order, delivery, and confirmation |
| **CometBFT** | Byzantine fault-tolerant consensus engine used for sequencer ordering |
| **Traffic** | Byte-metered charges for synchronizer usage (megabytes per USD) |
| **Scan** | Public indexing service collecting transaction records for query and analytics |
| **Splitwell** | Example bill-splitting application built on Splice wallet infrastructure |
| **DarResources** | Auto-generated Scala file mapping DAR package metadata — run `sbt updateDarResources` after DAR changes |

## Key Directories

| Directory | Purpose |
|-----------|---------|
| `apps/app/` | Main entry point (`SpliceApp.scala`), environment definition, integration tests |
| `apps/common/` | Shared Scala infrastructure (stores, triggers, auth, HTTP, ledger connection) |
| `apps/common/frontend/` | Shared React component library (`common-frontend`) |
| `apps/sv/` | SV app — governance, mining rounds, amulet price voting |
| `apps/validator/` | Validator app — user management, wallet, validation |
| `apps/scan/` | Scan app — indexing, aggregation, public query API |
| `apps/wallet/` | Wallet module (embedded in Validator) — transfers, subscriptions |
| `apps/splitwell/` | Splitwell app — bill-splitting example |
| `apps/ans/` | ANS frontend |
| `apps/dar-resources-generator/` | Generates `DarResources.scala` from built DARs |
| `daml/` | All DAML smart contract packages (28 packages) |
| `token-standard/` | Generic token interfaces (16 packages) |
| `canton/` | Vendored Canton OS fork (see `CANTON_CODE_CHANGES.md`) |
| `cluster/` | Deployment: Helm charts, Pulumi IaC, Docker images, cluster config |
| `project/` | SBT build definitions (`BuildCommon.scala`, `DamlPlugin.scala`, `Dependencies.scala`) |
| `nix/` | Nix flake and dev environment definition |
| `scripts/` | 60+ operational scripts (code quality, deployment, utilities) |
| `docs/src/` | Sphinx RST documentation (91 files) |
| `network-health/` | CometBFT log format definitions |
| `load-tester/` | TypeScript load testing tool |

## Base Package

`org.lfdecentralizedtrust.splice`

Sub-packages per app: `.sv`, `.validator`, `.scan`, `.wallet`, `.splitwell`
Common: `.admin`, `.auth`, `.automation`, `.config`, `.environment`, `.http`, `.store`, `.util`

## App Architecture Summary

Each app follows the same pattern:
1. **Config** (`{App}AppBackendConfig`) — HOCON, deserialized via PureConfig
2. **Bootstrap** (`{App}AppBootstrap`) — creates app instance from config
3. **Node** (extends `NodeBase` / `Node`) — lifecycle management, ledger connection
4. **Store** (`Db{App}Store`) — PostgreSQL ACS/TxLog storage, contract queries
5. **Automation** (`{App}AutomationService`) — registers triggers for background processing
6. **HTTP Handlers** (`Http{App}Handler`) — Pekko HTTP endpoints generated from OpenAPI specs
7. **Metrics** (`{App}Metrics`) — Prometheus counters, timers, gauges

## Quick Commands

```bash
# Build
sbt compile                    # Compile production code
sbt Test/compile               # Compile production + test code
sbt bundle                     # Create release bundle
sbt damlBuild                  # Build all DAML DARs
sbt damlTest                   # Run DAML script tests
sbt updateDarResources         # Regenerate DarResources.scala from DARs

# Format & Lint
sbt format                     # scalafmt
sbt formatFix                  # scalafmt + scalafix + npmFix + headerCreate
sbt lint                       # Full lint check (does not apply fixes)

# Test
sbt 'apps-app/testOnly *MyTest*'              # Run specific integration test
sbt 'apps-app/testOnly *MyTest* -- -z "desc"' # Filter by test description
sbt apps-frontends/npmTest                     # Frontend unit tests
sbt checkErrors                                # Check logs for unexpected errors

# DAR Management
sbt damlDarsLockFileUpdate     # Update dars.lock after DAML changes
sbt damlCheckProjectVersions   # Verify DAML version consistency
sbt updateTestConfigForParallelRuns  # Update test parallelization config

# Canton
./start-canton.sh              # Start wallclock Canton
./start-canton.sh -s           # Start simtime Canton
./start-canton.sh -m           # Start minimal Canton (frontend tests)
./stop-canton.sh               # Stop Canton

# Deployment
make build                     # Build everything (DARs + bundle + cluster)
make clean-all                 # Full clean (target, .daml, node_modules)
make cluster/helm/test         # Run Helm chart tests
make cluster/pulumi/test       # Run Pulumi unit tests
```

## CI Conventions

- Include `[ci]` in commit message to opt-in to CI (default: cancelled)
- Include `[static]` for static-tests-only runs
- Include `[force]` to skip CI but allow merge
- Include `[breaking]` to skip upgrade compatibility tests
- Include `[bft]` to run with Canton BFT sequencer
- All commits require `Signed-off-by:` line (DCO)
- PRs are **squash-merged** — PR title becomes the commit on `main`
