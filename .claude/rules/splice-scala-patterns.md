# Splice Scala Patterns

## App Lifecycle

### Entry Point
- `SpliceApp extends CantonAppDriver` — loads `SpliceConfig`, creates `SpliceEnvironment`
- `SpliceEnvironment` instantiates all app instances (SV, Validator, Scan, Splitwell)

### Node Base Classes
- `NodeBase[State]` — abstract base managing ledger connections, service users, package management, metrics
- `Node[State, PreInitState]` — generic base with standard init lifecycle

### Initialization Lifecycle
```
preInitializeBeforeLedgerConnection()  →  preInitializeAfterLedgerConnection()  →  initialize()
```
- Each returns app `State` implementing `AutoCloseable & HasHealth`
- Bootstrap factories (`SvAppBootstrap`, `ValidatorAppBootstrap`, etc.) return `Either[CantonConfigError, App]`

## Store Patterns

### Hierarchy
```
AppStore (trait) — defines acsContractFilter, multiDomainAcsStore
  └── DbAppStore (abstract) — DB operations, ACS table, ingestion, serialization
      ├── DbSvDsoStore — DSO governance queries
      ├── DbValidatorStore — validator + wallet queries
      ├── DbScanStore — indexing, aggregation
      └── DbSplitwellStore — splitwell-specific queries
```

### Store Constructor Pattern
All `Db*Store` classes take:
- `key: Store.Key` (party identifiers)
- `storage: DbStorage` (Canton DB connection)
- `loggerFactory: NamedLoggerFactory`
- `retryProvider: RetryProvider`
- `domainMigrationInfo: DomainMigrationInfo`
- `participantId: ParticipantId`

### Common Query Methods
```scala
lookupContractById[T](companion)(id): Future[Option[ContractWithState[TCid, T]]]
findAnyContractWithOffset[T](companion): Future[QueryResult[Option[...]]]
listContracts[T](companion, limit): Future[Seq[Contract[TCid, T]]]
streamAssignedContracts[T](companion): Source[...]
```

### Store Descriptors
`StoreDescriptor(version, name, party, participant, key)` — bump `version` to trigger full ACS re-ingestion.

### Multi-Domain ACS
- `MultiDomainAcsStore` ingests from multiple synchronizers
- Contract states: `Assigned(domain)`, `Pending`, `Archived`
- Query results wrapped in `QueryResult[T]` with offset for pagination

## Automation / Trigger Patterns

### Trigger Base Types
1. **`OnAssignedContractTrigger[TCid, T]`** — process ready contracts from ACS stream
2. **`PollingTrigger`** — periodic polling with `poll(): Future[Option[TaskOutcome]]`
3. **`PeriodicTaskTrigger`** — structured periodic task execution
4. **`SourceBasedTrigger[T]`** — Pekko Stream source consumption
5. **`MultiDomainExpiredContractTrigger`** — auto-archive expired contracts

### Trigger Outcomes
```scala
TaskSuccess(description: String)
TaskAskRetry(reason: String)
TaskFailure(errors: Seq[String])
```

### AutomationService Pattern
```scala
abstract class AutomationService extends HasHealth with FlagCloseableAsync {
  def registerTrigger(trigger: Trigger): Unit
  def registerService(service: BackgroundService): Unit
}
```
- Each app has a companion object with `expectedTriggers: Set[String]` for validation
- Config: `AutomationConfig` with `pausedTriggers: Set[String]`

### Writing a New Trigger
1. Extend appropriate trigger base class
2. Constructor: `TriggerContext`, app store, `SpliceLedgerConnection`
3. Implement task logic returning `TaskOutcome`
4. Register in the app's `AutomationService`

## HTTP/gRPC API Patterns

### Framework
Pekko HTTP (formerly Akka HTTP) with OpenAPI code generation via Guardrail.

### Handler Pattern
```scala
class HttpSvAdminHandler(
    config: SvAppBackendConfig,
    svStoreWithIngestion: AppStoreWithIngestion[SvSvStore],
    dsoStoreWithIngestion: AppStoreWithIngestion[SvDsoStore],
    ...
)(implicit ec: ExecutionContextExecutor, tracer: Tracer)
    extends SvAdminResource.Handler[AdminUserRequest]
    with Spanning with NamedLogging
```

### Standard Handler Method Flow
1. Extract auth context: `implicit val AdminUserRequest(traceContext) = extracted`
2. Wrap in span: `withSpan(s"$workflowId.methodName") { _ => _ => ... }`
3. Query stores, execute ledger commands
4. Return typed response: `respond.OK(result)` or `respond.NotFound(...)`

### Auth Extractors
- `AdminAuthExtractor` — admin-only (HMAC or RSA JWT)
- `ActAsKnownPartyAuthExtractor` — party-scoped JWT
- `UserWalletAuthExtractor` — wallet user auth

### Error Handling
`HttpErrorHandler` maps gRPC Status codes to HTTP:
- `NOT_FOUND` → 404, `ALREADY_EXISTS` → 409, `INVALID_ARGUMENT` → 400
- Response format: `{"message": "error description"}`

### API Route Prefixes
- SV Admin: `/admin/...`, SV Operator: `/operator/...`, SV Public: `/public/...`
- Validator: `/api/validator/...`, Scan: `/api/scan/...`, Wallet: `/api/wallet/...`

## Canton Ledger Integration

### Connection Types
- `BaseLedgerConnection` — read-only (ACS queries, package listing)
- `SpliceLedgerConnection` — read-write (exercise, submit, allocate parties)

### Exercising Choices
```scala
connection.exercise(
  contract,
  contractId => contract.contractPayload.exerciseSomeChoice(party, param1, param2),
  disclosedContracts = ...
)
```

### Contract Ingestion
- `UpdateIngestionService` streams transactions from Ledger API
- Initial catch-up via ACS snapshot (`GetActiveContractsRequest`)
- Contracts decoded and stored in PostgreSQL with domain assignment and state

### Party Management
- Primary party allocated during init (one per app service user)
- DSO party is multi-app governance party
- Users created via `ParticipantAdminConnection.allocateParty()`

## Configuration

### Format
HOCON via PureConfig. Type-safe deserialization with custom readers/writers.

### Structure
```
SpliceConfig
├── svApps: Map[InstanceName, SvAppBackendConfig]
├── validatorApps: Map[InstanceName, ValidatorAppBackendConfig]
├── scanApps: Map[InstanceName, ScanAppBackendConfig]
├── splitwellApps: Map[InstanceName, SplitwellAppBackendConfig]
├── parameters: CantonParameters
└── monitoring: MonitoringConfig
```

### Environment Variable Override
- Use `${?ENV_VAR}` substitution in HOCON
- Only environment variables are fully supported (not system properties)
- `ConfigTransforms.scala` handles programmatic transforms in tests

## Error Handling
- `HttpErrorWithHttpCode(code, message)` / `HttpErrorWithGrpcStatus(status, message)`
- Use Canton's `ErrorUtil` for internal errors
- gRPC-to-HTTP error mapping centralized in `HttpErrorHandler`

## Metrics
- Base: `SpliceMetrics` extending Canton `BaseMetrics`
- Per-trigger: `TriggerMetrics` (latency timer, iteration meter, completion meter)
- Per-store: `StoreMetrics` (ACS size gauge, ingestion meters)
- Export: Prometheus format via admin API endpoint
- Tracing: OpenTelemetry with `withSpan()` pattern

## Pekko Usage
- HTTP server lifecycle and route binding
- Streaming materialization for ledger transaction processing
- NOT used for actor-model business logic — Splice uses `Future`-based structured concurrency
- CORS via `cors()` directive
