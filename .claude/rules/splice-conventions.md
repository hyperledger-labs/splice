# Splice Conventions

## Git & PR Workflow

### Branch Naming
`<yourname>/<descriptivename>` (e.g., `bob/fix-footest/4242`)

### Merge Strategy
**Squash and merge** — PR title becomes the single commit on `main`.

### Squash Commit Message
- Title: concise, imperative mood ("Add external hash to Scan API", not "Added...")
- Body: brief summary of what/why, bullet points OK
- Reference issue: `Fixes #1234` or `Part of #1234`
- Remove CI tags (`[ci]`, `[static]`)
- Must include `Signed-off-by:` line (DCO)

### CI Opt-in
- `[ci]` in commit message — run full CI
- `[static]` — static tests only
- `[force]` — skip CI but allow merge
- `[breaking]` — skip upgrade compatibility tests

## Naming Conventions

### Domain Terms
- Use `amount` (not `quantity` or `number`) consistently
- Use `sender`/`receiver` (not `payer`/`payee`)
- Use `listXXX`, `acceptXXX`, `rejectXXX`, `withdrawXXX` for proposals/requests
- Flags: `enableXXX` (not `disableXXX` — avoid double negation)

### Protobuf
- Use `proto3` syntax
- Store contract IDs as `string` with `contract_id` suffix
- Store party IDs as `string` with `party_id` suffix
- Use plural names for `repeated` fields
- Avoid wrapping primitives in messages unless future extensibility needed
- Place `.proto` files in `src/main/protobuf`
- Prefer single `.proto` per service
- Reference generated Protobuf classes with package prefix (`v0.MyMessage`)

### Code Layout
- Place `.proto` files in `src/main/protobuf`
- Generated protobuf: reference with package prefix to avoid name conflicts

## DAML Conventions

### Changes Require Governance Vote
All DAML model changes on production require SV majority vote. Discuss with maintainers first.

### Lock Files
- `daml/dars.lock` — CI validates package IDs match
- Update after changes: `sbt damlDarsLockFileUpdate`

### Backwards Compatibility (Required)
- All changes must be backwards-compatible
- `ExtFoo` constructors are legacy — add new constructors directly now
- Enums must remain enums (all nullary constructors) — don't add non-nullary constructors
- See [Canton upgrading docs](https://docs.digitalasset.com/build/3.4/sdlc-howtos/smart-contracts/upgrade/)

### Numerics
- User-facing APIs: `scala.math.BigDecimal` (auto-converts from Int/Float)
- Protobuf: `string` (no native BigDecimal type). Convert via `Proto.encode/tryDecode`
- Ledger API: convert to `java.math.BigDecimal`
- Canonical example: `wallet.tap` command implementation

### Version Guards
- When DAML version X+1 is compiled but not yet voted in, backends must handle version X
- Tag tests: `@SpliceAmulet_0_1_9` for version-dependent tests
- Check active version endpoints in triggers and UIs

### Deprecation Rule
Wait for DAML version to be **effective on mainnet for one month** before bumping minimum and cleaning up code. Only if removal is not user-facing.

## Scala Conventions

### Java/Scala Type Conversions
- Use Scala types wherever possible
- Convert at boundaries only: `import scala.jdk.CollectionConverters.*` → `.asScala` / `.asJava`
- Delay Java conversion to last possible point, convert from Java as early as possible

### Unused Import Warnings
- Can be suppressed locally by creating `.disable-unused-warnings` file + `sbt reload`
- Not for CI — only during development

## Database Migrations

### Location
`apps/common/src/main/resources/db/migration/canton-network/postgres/stable/`

### Rules
1. **IMMUTABLE** — never modify deployed migration scripts (Flyway checksum verification)
2. Naming: `VXXX__descriptive_title.sql`
3. New columns: make NULLABLE for backward compat; populate from JSONB
4. Re-ingestion: bump store descriptor `version` to force ACS re-ingest
5. Filter changes: increment store descriptor version

### Schema Patterns
- `store_descriptors` table — tracks all ACS/TxLog stores (JSONB)
- Template tables: `{app}_acs_store` inherits from `acs_store_template`
- Index naming: `{table}_sid_mid_pn_tid_{column_abbrev}` (sid=store_id, mid=migration_id, etc.)
- Partial indexes exclude NULL values

### Store Descriptor
```scala
StoreDescriptor(version: Int, name: String, party: PartyId, participant: ParticipantId, key: Map[String, String])
```
Bump `version` when schema or contract filters change.

## OpenAPI / Scan API

### BFT-Consensus Types (Critical)
Update history types are serialized to JSON and compared across SVs for BFT consensus. **Any** schema change breaks consensus.

### Adding Fields to BFT Types
- **Required fields**: Gate behind threshold record time so all SVs switch simultaneously
- **Optional fields**: Use `Option[OmitNullString]` + `omitWhenNone` helper (not bare `Option[_]` — circe emits `null` which breaks JSON equality)
- Add new types to `UpdateHistoryOmitNullStringComplianceTest`
- See `ScanJsonSupport.scala` for custom encoders

## Configuration (HOCON)

### Rules
- Only environment variables are fully supported for overrides (not system props)
- Use `${?ENV_VAR}` substitution syntax
- Config printed via `scripts/print-config.sh FILE`

## Frontend

### Workspace Structure
- Root: `apps/package.json` — npm workspaces
- Shared lib: `apps/common/frontend/` (`common-frontend`)
- Each app: own `tsconfig.json` inheriting from root
- Required scripts per package: `build`, `fix`, `check`, `start`

### Common Libs
- Import: `import { ... } from '@lfdecentralizedtrust/splice-common-frontend'`
- Uses barreling technique (`index.ts` exports all modules)
- Install: `npm install @lfdecentralizedtrust/splice-common-frontend -w my-workspace-pkg`

### Auth
- RS256 (OIDC) for production, HS256 (unsafe) for local dev
- Config via `window.splice_config` runtime injection
- `react-oidc-context` for OIDC flow

## Logging

### Format
- `.clog` extension (Canton Log format, JSON structured)
- `--log-encoder json --log-file-name log/splice-node-{suffix}.clog`
- NamedLoggerFactory with context propagation
- OpenTelemetry tracing integration

### Inspection
- `lnav log/canton_network_test.clog` (provided by direnv)
- Filter: `:filter-in config=<config-id>`, `:filter-out RequestLogger`
- SQL: `:filter-expr :log_text LIKE '%pattern%'`

## Metrics

### Framework
- Prometheus export via admin API
- OpenTelemetry exponential histograms (160 max buckets)
- Base: `SpliceMetrics` → per-app `{App}Metrics`
- Per-trigger: `TriggerMetrics` (latency, iterations, completions)
- Per-store: `StoreMetrics` (ACS size, ingestion rate)

### Definition Pattern
```scala
val myMetric: Timer = metricsFactory.timer(
  MetricInfo(prefix :+ "my-metric", "Description", Latency, "Details...")
)
```

## TODO Comments
- Format: `TODO(#ISSUE_NUMBER): description`
- Tracked by `scripts/check-todos.sh` in CI
