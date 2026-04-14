# Splice Testing Patterns

## Test Framework Hierarchy

### Base Classes
- **`IntegrationTest`** — shared Canton instance across test cases (fast, use when possible)
  - Extends `BaseIntegrationTest[SpliceConfig, SpliceEnvironment]` + `SharedSpliceEnvironment` + `TestCommon`
- **`IntegrationTestWithIsolatedEnvironment`** — fresh Canton per test method (slow, clean isolation)
- **`FrontendIntegrationTest`** — shared Canton + Selenium/Firefox
- **`FrontendIntegrationTestWithIsolatedEnvironment`** — isolated + Selenium

### TestCommon Trait (key utilities)
- `actAndCheck(description, action)(checkDescription, check)` — perform action then retry check until success
- `silentClue()` / `silentActAndCheck()` — suppress error logs inside `eventually()` loops
- `perTestCaseName(base)` — unique names per test case for shared environment isolation
- `assertInRange()`, `beWithin()`, `beAround()`, `beEqualUpTo()` — BigDecimal matchers

## Integration Test Location
`apps/app/src/test/scala/org/lfdecentralizedtrust/splice/integration/tests/`

## Writing Integration Tests

### Environment Definition
```scala
override def environmentDefinition: SpliceEnvironmentDefinition =
  EnvironmentDefinition
    .simpleTopology1Sv(this.getClass.getSimpleName)
    .withStandardSetup
    .withAllocatedUsers()
    .withInitializedNodes()
    .addConfigTransforms((context, config) =>
      ConfigTransforms.updateInitialTickDuration(duration)(config)
    )
    .withAmuletPrice(priceInUsd)
    .withManualStart  // don't auto-start apps
```

### Common Transformations
- `.withAmuletPrice(n)` — set amulet USD price
- `.withSequencerConnectionsFromScanDisabled()`
- `.withManualStart` — don't auto-start apps
- `.withStandardSetup` — standard initialization
- `.withAllocatedUsers()` — pre-create DAML users
- `.withTrafficTopupsEnabled`
- `.addConfigTransforms(...)` — arbitrary config modifications

### Test Topology
- Main: `apps/app/src/test/resources/simple-topology.conf` (includes `simple-topology-1sv.conf`)
- Port scheme: `<NetworkIndex><NodeIndex><APIIndex>` (5 digits)
  - Network: 5=default, 15=simtime, 25=toxiproxy
  - Node: 1=SV1, 2=SV2, 5=Alice, 6=Bob, 7=Splitwell
  - API: 01=Ledger, 02=Admin, 14=SV Public

### Client References (from CommonAppInstanceReferences)
```scala
sv1Backend                // SvAppBackendReference
aliceValidatorBackend     // ValidatorAppBackendReference
aliceWalletClient         // WalletAppClientReference
aliceWalletClient.tap(amount)
aliceWalletClient.balance()
```

### Act and Check Pattern (PREFERRED for async assertions)
```scala
val (actionResult, checkResult) = actAndCheck(
  "Alice sends payment",
  aliceWallet.sendPayment(bob, 100)
)(
  "Payment appears in Bob's history",
  _ => bobWallet.getTransactions() should contain(...)
)
```

### Silent Clues (prevent spurious log errors)
```scala
// Inside eventually() blocks, use silent variants:
silentClue("checking balance") {
  someCheck() // won't log TestFailedException as error
}
```

### Simulated Time Tests
- Require: `EnvironmentDefinition.simpleTopology1SvWithSimTime()`
- Mixin: `TimeTestUtil` trait
- Advance: `advanceTime(duration)`, `advanceRoundsToNextRoundOpening()`
- Call `advanceTimeByPollingInterval()` to trigger polling-based triggers
- Do NOT advance time on wallclock Canton (will fail)
- Do NOT advance too much (contracts may expire unexpectedly)

## Error Handling in Tests

### SuppressingLogger
Used to suppress expected errors/warnings that would otherwise fail CI:
```scala
loggerFactory.assertLogsSeq(SuppressionRule.LevelAndAbove(Level.ERROR)) {
  { somethingThatLogsError() }
}
```

### CI Log Checking
- `sbt checkErrors` validates logs for unexpected errors/warnings
- Logs: `log/canton_network_test.clog` (JSON structured)
- Inspect: `lnav log/canton_network_test.clog` (provided by direnv)
- Filter: `:filter-in config=<config-id>`, `:filter-out RequestLogger`

## DAML Version Guard Tags

```scala
// Tag tests requiring specific DAML versions
@org.lfdecentralizedtrust.splice.util.scalatesttags.SpliceAmulet_0_1_9
class MyTest { ... }

// Or per-test:
"test feature" taggedAs Tags.SpliceAmulet_0_1_14 in { ... }
```
Tests excluded when running against older DAML versions.

## Frontend Tests

### Selenium/Firefox
- Headless by default (`-headless` arg), set `false` for debugging
- `withFrontEnd(name) { implicit webDriver => ... }` — lifecycle management
- `waitForQuery(selector)`, `eventuallyClickOn(selector)` — common interactions
- `screenshot()` — visual debugging (works headless)
- Console logs include W3C trace parent for correlation

### Port Mapping
```
30XX - Wallet (00=Alice, 01=Bob, 02=Charlie)
31XX - ANS
32XX - SV UI (11=SV1, 12=SV2)
33XX - Scan
34XX - Splitwell
```

## Unit Tests

### Location
`apps/{app}/src/test/scala/`
- Extend ScalaTest `AsyncWordSpec` or `WordSpec` + `BaseTest`
- No `IntegrationTest` inheritance, no full Canton topology
- Can use mocks and stubs

## DAML Script Tests

### Location
`daml/splice-*-test/daml/Splice/Scripts/`

### Pattern
```daml
testSomething : Script () = do
  (app, dso, (sv1, sv2, sv3, sv4)) <- initMainNet
  [(dsoRulesCid, _)] <- query @DsoRules dso
  submitMulti [sv1] [dso] $ exerciseCmd dsoRulesCid DsoRules_RequestVote with ...
  submitMultiMustFail [sv1] [dso] $ exerciseCmd ...
```
Run: `sbt damlTest`

## Vitest (Frontend Unit Tests)

### Location
`apps/{app}/frontend/src/__tests__/`
- Framework: Vitest 3.1.1 + @testing-library/react + MSW for mocking
- Setup: `src/__tests__/setup/setup.ts` (MSW server, window.splice_config, crypto)
- Run: `sbt apps-frontends/npmTest` or `npm test` in app directory

## Test Plugins

Located: `apps/app/src/test/scala/.../integration/plugins/`
- `UpdateHistorySanityCheckPlugin` — validates ACS consistency
- `EventHistorySanityCheckPlugin` — checks event history
- `TokenStandardCliSanityCheckPlugin` — verifies token standard CLI
- `ResetDecentralizedNamespace` — cleans SV namespaces between tests
- `UseToxiproxy` — network chaos injection

## Test Parallelization

- Test names tracked in `test-full-class-names-*.log` files
- Updated by `sbt updateTestConfigForParallelRuns` on test file changes
- Must be committed with new test additions (enforced by pre-commit hook)

## Key Best Practices

1. Always use `actAndCheck()` for async assertions (not bare `eventually()`)
2. Use `silentClue()` inside `eventually()` to prevent spurious log errors
3. Use `perTestCaseName()` for names in shared environment tests
4. Prefer shared environment (fast) over isolated (slow) unless test requires it
5. Run `sbt updateTestConfigForParallelRuns` when adding new tests
6. Run `sbt checkErrors` after test runs to catch unexpected warnings
