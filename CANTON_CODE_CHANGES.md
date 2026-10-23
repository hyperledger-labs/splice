# What is this file?

Currently, we have a copy of the Canton OS files in `canton/`.
We want to switch to eventually reusing this Canton code via a library
setting. The purpose of this file is to track all changes we
make to the Canton file copies before then,
to know which and/or what changes we'll need to upstream before the switch.

# Changes

## Methods or classes with changed visibility
* `ConsoleEnvironment.startupOrderPrecedence` made public
* `BaseLedgerApiAdministration.consoleEnvironment` made public
* `BaseLedgerApiAdministration.timeouts` made public
* `LocalInstanceReference.adminCommand` made public
* `LocalInstanceReference.nodes` made `protected[console]`
* `InstanceReference.instanceType` changed from `protected[canton]` to `protected` so Splice can override it
## Misc
* Generalization of `MetricsFactory`
* Removed a trailing comma in many places because the CC Scala compiler doesn't like it (e.g. `.authorize(op, domain, mediator, side, key.some, )` -> `.authorize(op, domain, mediator, side, key.some)`)
* Added `org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition` to `BaseIntegrationTest.scala`
* Added `resetRetriesAfter` option to `Backoff` retry policy
* Changed default concurrency limit in `ConcurrentEnvironmentLimiter` to 1
* LogReporter logs location, message and throwable on TestFailed event.
* Added an optional `darFile` parameter to `ParticipantAdminCommands.UploadDar` so we can more conveniently upload DARs from weird locations (like JARs).
* `ParticipantAdminCommands.ImportPartyAcs` now takes an InputStream instead of a file for greater versatility.
* Decreases the `maxPollInterval` to 100ms from 5s, in the `eventually` methods in `BaseTest`
* Added `BaseTest.DefaultEventuallyTimeUntilSuccess`, the default `timeUntilSuccess` of `object BaseTest`'s `eventually`.
* Compute the minimum poll interval used in `eventually` to 10% of the `maxPollInterval`
* Added `suppressFailedClues` to `BaseTest` trait.
* Support specifying `darData` on `upload.dars`.
* Turned `synchronize_topology` into a noop.
* Added priority shutdown tasks in `OnShutdownRunner` that run before other tasks, and added `setAsClosing` method.
* Disabled `logConfigOnStartup` by default as it also logs secrets.
* Changed `metrics.filterByNodeAndAttribute` in `InstanceReference ` to filter by `node_name` instead of `node` to match the Splice metrics
* Marked protocol version 37 as stable in `ProtocolVersion` (and in `stableProtocolVersions` build info in `BuildCommon`).
* Worked around `BaseIntegrationTest.assertThrowsAndLogsCommandFailures` only supporting Canton's `EnvironmentDefinition` (and thus not Splice's).
## Build system
* Added refs to GH issues in project/DamlPlugin.sbt for two bugs
* Added support for `damlDependencies` in SBT DamlPlugin
- Added logic to DamlPlugin to support data-dependency paths compatible with Daml Studio & SBT
* Changed cache detection logic for `damlBuild` in SBT DamlPlugin to file-hash instead of the default modification time.
* Replaced the `SequencerConfig` with a placeholder due to a missing project dependency on `community-reference-sequencer`
* Added `org.lfdecentralizedtrust.splice` to `logback-test.xml`
* `DbStorageSetup.useTestContainer` also uses the Postgres provided by CI instead of a test container when running on GitHub Actions (`GITHUB_ACTION` set).
* The participant admin workflow Daml projects (`canton-builtin-admin-workflow-ping`, `canton-builtin-admin-workflow-acs-replication-alpha`) set `sdk-version` and `--target=2.1` in their `daml.yaml`.
  Upstream uses `override-components` with `$DAML_VERSION` and `--target=2.2`; with our dpm SDK bundle this fails both when `DAML_VERSION` is unset and when it is set (dpm does not allow mixing components and an SDK bundle).
