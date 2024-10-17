# Integration tests

Integration tests are the backbone of our testing strategy for Splice.
This folder contains all such tests.

In the following, we describe non-obvious aspects of (some of) our integration tests,
as well as common pitfalls when developing and debugging integration tests for Splice.
For more general documentation about testing and running tests please consult the [main README](/README.md#testing).

## Wait for updates

It's a common pattern to perform an action in an integration test and then assert that the action lead to a desired result.
In a distributed system with non-blocking actions it often can't be guaranteed that the result of an action will become visible immediately.
When the desired outcome should be reflected in the ACS of a Canton participant, for example,
both processing and communication delays can make it necessary for tests to wait for checks to (`eventually`) complete.

A best practice is to use the `actAndCheck` method for implementing above pattern.
The "check" part of `actAndCheck` already adds an implicit wait.
Example usage:

```
  val (initialPaymentCid, _) = actAndCheck(
    "Alice accepts the request",
    aliceWallet.acceptSubscriptionRequest(request.contractId),
  )(
    "Request disappears from Alice's list",
    _ => {
      aliceWallet.listSubscriptionRequests() shouldBe empty
    },
  )
```

To prevent false negatives and flakes: Use `actAndCheck`.
If this doesn't match your test's logic: Consider wrapping checks in an `eventually`.
If you want to retry a check on failures that are different from `TestFailedException`: Use `eventuallySucceeds`.

## Ensure that test failures print helpful messages

- Use `collection should have size <expected-size>`, as that prints the collection and its size on a failure instead
  of just printing `0 not equal to 1`, which is shown if you use `collection.size shouldBe <expected-size>`
- Use `inside(complexStructure) { <assertions on fields/properties of complexStructure> }` to ensure `complexStructure` is
  printed in addition to the assertion failure on any of the checks on the fields of that structure.

## Time-based tests

For testing time-dependent functionality such as subscription payments or round automation,
we sometimes need to control time within an integration test.
Running `/start-canton.sh` therefore launches not one Canton instance, but two:
one running with regular wall-clock time, and one running with a `simclock` that can be controlled from within our tests.

To use `canton-simtime` instead of `canton`, a test needs to use an appropriate environment definition such as (truncated):

```
  override def environmentDefinition: EnvironmentDefinition = {
    EnvironmentDefinition
      .simpleTopology1SvWithSimTime(this.getClass.getSimpleName)
```

Time-based test classes typically mixin the [`TimeTestUtil`](/apps/app/src/test/scala/org/lfdecentralizedtrust/splice/util/TimeTestUtil.scala) trait which provides the `advanceTime` method (among others).

Time in time-based tests flows in non-obvious ways:

- *Ledger time* doesn't flow; it only advances when explicitly triggered via `advanceTime` or a similar method.
- All other operations run normally; i.e.: communication, code execution and any periodic processes that are not triggered by ledger time (such as our [retry logic](/apps/common/src/main/scala/org/lfdecentralizedtrust/splice/environment/RetryProvider.scala)) still run in "real time".
- [`PollingTrigger`](/apps/common/src/main/scala/org/lfdecentralizedtrust/splice/automation/Trigger.scala)s are triggered by ledger time.
  On *each* advancement of simulation time, the status of *all* polling triggers is checked and triggers that are due are activated.
  Use the `advanceTimeByPollingInterval` method if you want to advance time by the minimal duration sufficient to cause polling-based triggers to be activated.

A common source of errors in time-based tests is that polling triggers don't trigger.
When waiting for a [condition to become true](#wait-for-updates), for example,
the longest real-time wait won't help if an automation that is required simply doesn't trigger because simulation time did not advance.
Solution: Add an invocation of `advanceTimeByPollingInterval` to the beginning of the "check" part of your `actAndCheck`
(respectively, to the beginning of your `eventually` block).

Another frequent error is to accidentally `advanceTime` too much.
This can, for example, cause contracts with expiration times to expire and get archived.

## Shared test environments

Starting up our [test topology](apps/app/src/test/resources/simple-topology.conf) is time-intensive.
Whenever a test suite affords it (which should be most of the time),
we therefore want to do this only once for the whole test suite.
This is realized by defining test classes as `extends IntegrationTestWithSharedEnvironment` / `extends FrontendIntegrationTestWithSharedEnvironment(...)`.
Use tests with a shared environment whenever it's possible!
It prevents our CI waiting times from exploding.

When working in a test class that uses a shared environment, you must take a bit of extra care to ensure that test cases don't conflict with each other by polluting the environment's state.
For example:
Whenever you are registering new identifiers, such as ANS names, you must wrap them in a `perTestCaseName(...)` to avoid name collisions.
Many ledger API user names are automatically wrapped in this way when you are using references like `aliceWallet`.
Some aren't though - such as service users (for the SV and validator participants) that are allocated via a test's [environment definition](/apps/app/src/test/scala/org/lfdecentralizedtrust/splice/integration/EnvironmentDefinition.scala).

## Errors and warnings in log files

As part of CI, we run `sbt checkErrors` to make sure that all logs produced during the execution of our tests are free of exceptions, warnings and errors.
A test that produces problematic log entries is thereby considered a failing test.
Note that it's not always obvious which test is responsible for a problematic log entry.
The log checking happens after the tests have completed, so that even a test that is reported as "passed" on CI could be responsible.

If logging an error or warning is an expected and desirable outcome of your test, use `loggerFactory.assertLogs` or one of its cousins.

## When to run preflight checks

We use [preflight checks](/README.md#running-the-preflight-check) to make sure that our cluster deployments are fully operational and that the instructions in our [runbooks](/cluster/images/docs/README.md) work.
Preflight checks should run against a cluster deployment with a matching version.
Preparing such a deployment and running the tests takes a while, occupying one of our clusters.
We therefore don't *require* an automated preflight check to have completed on a PR before that PR can be merged to `main`.
Every commit on `main` is tested via a preflight check, however,
so if you have the suspicion that your PR might affect our cluster deployments and/or the instructions in the runbook:
Run the preflight checks before clicking on "merge"!

If in doubt: Just run the preflight check once before you set your PR to "ready".
(It's a good exercise for newjoiners ;))
