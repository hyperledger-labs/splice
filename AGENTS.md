# Agent Instructions

This document provides instructions for AI coding agents (GitHub Copilot, Claude Code, and others) to follow when assisting with development in this repository.

## Repository Overview

* `apps/` holds the Scala backends (packages under `org.lfdecentralizedtrust.splice`) and React frontends: `validator` (also serves the wallet and ANS backends), `sv`, `scan`, `splitwell`, the shared `common` (`apps-common`; `apps/common/sv` is SV-specific), and `app` (`apps-app`: the release bundle, entry point `SpliceApp.scala`, and the integration tests).
* Stores ingest the ledger update stream asynchronously. Automation runs as `*Trigger` classes that react to ledger state and submit follow-up commands.
* `daml/` holds the Daml packages, one sbt project each. `token-standard/` defines the CIP-0056 token standard APIs, which `daml/splice-amulet` implements.
* `canton/` is a copy of open-source Canton, not Splice code. Avoid editing it; if you must, record the change in `CANTON_CODE_CHANGES.md` (see "Bumping Our Canton fork" in [MAINTENANCE.md](MAINTENANCE.md)).
* `cluster/` holds the Helm charts, Pulumi deployment code, and Docker Compose setup.

## Testing

Every contribution must be tested in an automated test. For further details see the [Testing README](TESTING.md).

## Building and Running Tests

* Run `sbt` inside the direnv/nix dev shell, e.g. `direnv exec . sbt -batch "apps-scan/testOnly <FullyQualifiedSuite>"`. `-batch` avoids the interactive prompt; redirect output to a file and grep for `^\[error\]` and `Tests:`.
* Test code compiles with `-Xfatal-warnings` and `-Wunused:privates`/`-Wunused:params`, so an unused private member or parameter fails `Test/compile`.
* To format specific files: `sbt "scalafmtOnly <files...>"`.
* Integration tests under `apps/app/src/test` need a running Canton from `./start-canton.sh`, including suites based on `IntegrationTestWithIsolatedEnvironment`. Without it they fail with `java.io.FileNotFoundException: canton.tokens`.
  * Start only what the test needs: `./start-canton.sh -d -w` for wallclock tests, `-s` for simtime tests. Add `-p local` to use a local Postgres instead of Docker.
  * `start-canton.sh` manages Postgres itself; do not start it separately with `scripts/postgres.sh`. Stop with `./stop-canton.sh` (pass the same Postgres mode, e.g. `./stop-canton.sh local`).
  * Restart Canton (`./stop-canton.sh` then `./start-canton.sh`) whenever `nix/canton-sources.json` changes, e.g. after a checkout or rebase: it pins the Canton version that `start-canton.sh` runs.
  * Run a single test with `sbt "apps-app/testOnly <FullyQualifiedSuite> -- -z \"<test name>\""`.
  * Suites named `*FrontendIntegrationTest` drive the frontends through Selenium and also need `./start-frontends.sh`.
* Do not run bare `sbt test`; use `testOnly` and leave the full suite to CI.
* CI runs `sbt checkErrors` after tests, which fails on WARN/ERROR lines in the logs under `log/` that don't match `project/ignore-patterns/`. The logs are not rotated, so stale errors from earlier runs also show up; clear `log/` before a run you want to check. The main logs are `log/canton_network_test.clog` (test run and Splice apps) and `log/canton.clog` / `log/canton-simtime.clog` (Canton).
* `sbt formatFix` applies scalafmt, scalafix, npm fixes and copyright headers (`make format` additionally formats cluster code). `sbt lint` runs the static checks; note that its `scalafixAll` step can rewrite files.
* If dependency resolution fails with a 404 for a Canton snapshot artifact (e.g. `com.daml:bindings-java:<version>-snapshot...`), the pinned snapshot has likely expired; rebase onto the latest `main` and reload direnv.

## Writing Integration Tests

* Stores ingest asynchronously: never assert on store state immediately after an action. Use `actAndCheck`, `eventually` or `eventuallySucceeds`.
* All test cases of a suite extending `IntegrationTest` share one environment (`IntegrationTestWithIsolatedEnvironment` creates one per test case). Use `perTestCaseName(...)` for party and other names to avoid collisions between test cases.
* Wrap actions that are expected to log warnings or errors in `loggerFactory.assertLogs` (or a related `SuppressingLogger` method); otherwise `checkErrors` fails in CI.
* Simulated-time tests mix in `TimeTestUtil` and move the clock with `advanceTime` and related helpers.

## DB Migrations

Refer to [the main README on migrations](apps/common/src/main/resources/db/migration/README.md). Most importantly, never modify a deployed migration script, not even its comments; add a new migration instead.

## Daml Changes

### Daml Lock Files

If you make intentional changes in Daml code, run `sbt damlDarsLockFileUpdate` and commit the updated `dars.lock` file along with your dar changes.

### Backwards-compatible Daml changes

All Daml changes must be backwards-compatible. See the [Smart Contract Upgrading Reference from the Canton docs](https://docs.canton.network/appdev/deep-dives/smart-contract-upgrading-reference).

When adding to enums, make sure to only add further nullary constructors to types that only have nullary constructors.

Backends must keep working while only an older Daml version is voted in. Guard version-specific behavior, and tag tests that need a newer package with the matching scalatest tag from `org.lfdecentralizedtrust.splice.util.scalatesttags` (e.g. `SpliceAmulet_0_1_19`) so that the compatibility runs skip them. See "Daml Version Bump" and the sections after it in [DEVELOPMENT.md](DEVELOPMENT.md).

### Package Versions

When you change a Daml package, bump the version of that package and of every package that (transitively) depends on it, e.g. with `git fetch origin && sbt damlBumpPackageVersions`. Then run `sbt updateDarResources`; CI checks that the generated `DarResources.scala` files are up to date.

### Daml Numerics

- To represent Daml `Numeric`s for any user facing APIs (console commands), use `scala.math.BigDecimal`.
- To represent Daml `Numeric`s in Protobuf, use `string`s. Conversions to and from `string`s should occur via `org.lfdecentralizedtrust.splice.util.Proto.encode/tryDecode`.
- When interacting with the Ledger API, convert Scala `BigDecimal`s to Java `BigDecimal`s.
- Refer to the `wallet.tap` command implementation for the canonical handling of Daml Numerics.

## Message Definitions

* All Protobuf definitions must use `proto3`.
* Avoid wrapping primitive types in a message structure unless future extensibility will likely be required.
* Use a plural name for `repeated` fields.
* Use `string` fields with a suffix `contract_id` to store contract ids.
* Use `string` fields with a suffix `party_id` to store party ids.

## Config Parameters

* Name flags as `enableXXX` instead of `disableXXX` to avoid a double negation.
* Scala config fields are camelCase and map to kebab-case HOCON keys automatically.
* Only environment variables are fully supported for overriding configuration, not system properties.
* When adding an app config option, check whether it also needs wiring through `cluster/images/*/app.conf`, `cluster/compose/`, `cluster/helm/`, `cluster/pulumi/` and `docs/src/`. After cluster changes, regenerate `cluster/expected/` with `make update-expected`.

## Code Layout

* Place `.proto` files in `src/main/protobuf`.
* Prefer having a single `.proto` definition per service.
* Refer to generated Protobuf classes with a package prefix, e.g., `v0.MyMessage` instead of `MyMessage`.

## Domain Specific Naming

* Use `listXXX`, `acceptXXX`, `rejectXXX`, `withdrawXXX` for managing proposals, requests etc.
* Use the term `amount`, not `quantity` or `number`.
* Use `sender`/`receiver`, not `payer`/`payee`.

## Frontend Code

Frontend code projects are managed via `npm workspaces`.

### New Packages

To add a new package to the workspace:
1. Register its directory in the root-level `apps/package.json` workspaces key. The directory must contain its own `package.json`.
2. Add the new package to `build.sbt`.
3. Ensure the package contains at least the scripts `build`, `fix`, `check`, and `start`.
4. The new package will need its own `tsconfig.json` file that inherits from the root tsconfig.

### Common Libs

The `common-frontend` package (`@canton-network/splice-common-frontend`) contains common code in `apps/common/frontend`. You can add reusable utility functions, React components, or shared config here. Ensure anything added is exposed via the library's entrypoint, `index.ts`.

## Conversions between Java & Scala types

- Use Scala types wherever possible.
- Delay the conversion to Java types until the last possible point.
- Convert from Java to Scala as early as possible.
- To convert, import `scala.jdk.CollectionConverters.*` and use the `asScala` and `asJava` methods.

## Scala

- Production code bans partial `Iterable` methods such as `head`, `last`, `max` and `reduce` (wartremover `IterableOps`); use `headOption`, `maxOption`, `foldLeft` etc. instead.
  The exception is for the `NonEmpty` type, which features total versions of these such as `head1`.

## Contributing

- Every commit needs a DCO `Signed-off-by:` line; use `git commit -s`.
- PRs are squash-merged, so the PR title and description become the commit message.
- On branches in this repository, CI only runs if the last commit message contains `[ci]` (or `[static]` for static checks only). See [TESTING.md](TESTING.md) for other tags and for cluster tests.
