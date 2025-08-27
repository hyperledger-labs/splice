# What is this file?

Currently, we have a copy of the Canton OS files in `canton/`.
We want to switch to eventually reusing this Canton code via a library
setting. The purpose of this file is to track all changes we
make to the Canton file copies before then,
to know which and/or what changes we'll need to upstream before the switch.

# Changes

## Methods or classes with changed visibility
* `idHelper`, `tryParticipantNodeParametersByString`,
    `MetricsFactory.registerReporter`, `BaseIntegrationTest` made public
* `testingTimeService` made protected
* `PrettyInstances.treeOfIterable` and `PrettyInstances.prettyUidString` made public
* pretty-printing of hex-only contract-ids shortends the contract-id using readable-hash
* `ConsoleEnvironment.tracer` made public
* `BaseLedgerApiAdministration.consoleEnvironment` made public
* `BaseLedgerApiAdministration.optionallyAwait` made public
* `BaseLedgerApiAdministration.timeouts` made public
* `LedgerApiCommandRunner.ledgerApiCommand` made public
* `AdminCommandRunner.adminCommand` made public
* `DynamicDomainParameters` made public
* `nonNegativeFiniteDurationWriter` made public
* `CantonConfig.sequencerNodeParametersByStringX` made public
* `ActiveContract.loadFromByteString` made public
* ``PositiveFiniteDuration` config reader and writer made public
* `ProofOfOwnership` made public
## Misc
* Added support for interface filters in ledger api ACS commands. TODO (#638): This should be upstreamed.
* Generalization of `Environment`
* Generalization of `MetricsFactory`
* Removed a trailing comma in many places because the CC Scala compiler doesn't like it (e.g. `.authorize(op, domain, mediator, side, key.some, )` -> `.authorize(op, domain, mediator, side, key.some)`)
* Temporarily added a new release version in `CantonVersion.scala`
* Added `class UnitCommand` for admin commands that do not take arguments
* Adds some more utility methods to `PartyId`
* Added `org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition` to `LogEntry.scala`
* Support `readAs` in `commands.submit`, should be upstreamed
* Added `internalErrorGrpc` to `ErrorUtil`
* Generalized `ApiRequestLogger` to allow its definition to be reused to build an Api Client request logger.
* Make `applicationId` in console methods configurable everywhere
* Fixed a bug where `RemoteClock` was not waiting for a proper channel shudown
* Changed default log file name to `log/canton_network.clog` and `log/canton_network_test.clog`
* Changed default JSON logger configuration to try to shorten logger names to 30 characters
* Added `resetRetriesAfter` option to `Backoff` retry policy
* Switched FlagCloseable to append shutdown tasks so they get run in the order they're added
  rather than inverse order.
* Changed default concurrency limit in `ConcurrencyEnvironmentLimit` to 1
* Added a `cause` parameter to `CommandFailure`
* LogReporter logs location, message and throwable on TestFailed event.
* CommunityStorageFactory matches last branch on `CommunityDbConfig` instead of `DbConfig`
* Added an optional `darFile` parameter to `ParticipantAdminCommands.UploadDar` so we can more conveniently upload DARs from weird locations (like JARs).
* Decreases the `maxPollInterval` to 100ms from 5s, in the `eventually` methods in `BaseTest`
* Compute the minimum poll interval used in `eventually` to 10% of the `maxPollInterval`
* Added `suppressFailedClues` to `BaseTest` trait.
* Added `logAppVersion()` to `CantonAppDriver` abstract class and replaced canton version log with `logAppVersion()`.
* Added `tryFromProtoPrimitive` to `Member` trait.
* Support specifying `darData` on `upload.dars`.
* Turned `synchronize_topology` into a noop.
* Added support for passing trace-contexts via gRPC CallOptions to `TraceContextGrpc`
* Added priority shutdown tasks in `OnShutdownRunner` that run before other tasks, and added `setAsClosing` method.
* Disabled `logConfigOnStartup` by default as it also logs secrets.
* Changed `metrics.filterByNodeAndAttribute` in `InstanceReference ` to filter by `node_name` instead of `node` to match the Splice metrics
* Split `CommandFailure` into `InteractiveCommandFailure` and `CommandFailureWithDetails`.
* `Cli.logLastErrors` default changed from `true` to `false`.
* Added `rawStorage`, `setupStorage` and `DisableDbStorageIdempotency` to `DbTest` to allow better access to non-doubled writes.
* Added better logging of setup and cleanup failures in `DbTest`
## Build system
* Added refs to GH issues in project/DamlPlugin.sbt for two bugs
* Added support for `damlDependencies` in SBT DamlPlugin
- Added logic to DamlPlugin to support data-dependency paths compatible with Daml Studio & SBT
- Added (empty) `data-dependencies` to all daml.yaml files
* Changed cache detection logic for `damlBuild` in SBT DamlPlugin to file-hash instead of the default modification time.
* Changed `DbStorageSetup` to use `DbConfig with PostgresDbConfig` instead of `CommunityDbConfig.Postgres`.
* Stubbed the `SequencerConfig` due to a missing project dependency in on `communite-reference-sequecer`
* Added `org.lfdecentralizedtrust.splice` to `logback-test.xml`
