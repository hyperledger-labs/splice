# Table of Contents

- [Testing in Splice](#testing-in-splice)
  - [Introduction](#introduction)
  - [CI](#ci)
    - [Opting-in to CI](#opting-in-to-ci)
    - [Opting-out of CI](#opting-out-of-ci)
    - [Requesting Cluster Tests](#requesting-cluster-tests)
  - [Running Tests Locally](#running-tests-locally)
    - [Managing Canton for Tests](#managing-canton-for-tests)
      - [Issues on macOS](#issues-on-macos)
      - [Using a local build of Canton](#using-a-local-build-of-canton)
    - [Frontend Testing](#frontend-testing)
    - [Manual Frontend Testing](#manual-frontend-testing)
    - [Running and Debugging Integration Tests](#running-and-debugging-integration-tests)
    - [Testing App Behaviour Outside of Tests Without Running Bundle](#testing-app-behaviour-outside-of-tests-without-running-bundle)
    - [Testing Auth0 Auth Flows Locally](#testing-auth0-auth-flows-locally)
    - [Running The Preflight Check](#running-the-preflight-check)
      - [Configure Auth0 Environment](#configure-auth0-environment)
      - [Configure SV Web UI Password](#configure-sv-web-ui-password)
    - [Setting up `lnav` to Inspect Canton and CometBFT logs](#setting-up-lnav-to-inspect-canton-and-cometbft-logs)
    - [Handling Errors in Integration Tests](#handling-errors-in-integration-tests)
    - [Connecting external tools to the shared Canton instances](#connecting-external-tools-to-the-shared-canton-instances)
    - [Testing App Upgrades](#testing-app-upgrades)
  - [Deployment Tests](#deployment-tests)

# Testing in Splice

## Introduction

Splice code is tested in the following ways:

- Scala unit tests for specific app features. Typically located under `apps/<app name>/src/test`.
- Frontend unit tests in [Vitest](https://vitest.dev/guide/). Typically located under
  `apps/<app name>/frontend/src/__tests__`.
- Integration tests. Extensive integration tests are located under `apps/app/src/test/scala/`. Integration tests
  include tests that use frontends (whose names must end with `FrontendIntegrationTest`), and ones which do not
  (whose names ends with IntegrationTest).
- [Deployment tests](#deployment-tests) to catch errors in Helm and Pulumi before deploying to a cluster.
- Cluster tests. Various different cluster tests are currently run by Digital Asset on Splice codebase.
  This includes:
  - Deploying and testing a PR on a scratch cluster. See (TBD)
  - Various tests on every commit to `main`
  - Periodic tests on the latest state of `main`
  - A long-running large-scale cluster for various scale and performance tests.

## CI

### Opting-in to CI

At the moment, CI is by default being cancelled on commits, unless explicitly opted in. (CI jobs will report
the error `Build was canceled`.) To enable CI for your commit, please include the text `[ci]` in your commit
message.

### Opting-out of CI

In certain cases, it may be valid to allow a PR to be merged without going through CI.
While `[skip ci]` is supported, it does not allow the PR to be merged. To skip testing but
stil allow the PR to be merged, please include the text `[force]` in your commit message.

Alternatively, to run only static tests (and skip e.g. integration tests) on your PR,
include the text `[static]` in your commit message. This is recommended for, e.g. changes in
Pulumi deployment configurations, deployment scripts, etc.

### Requesting Cluster Tests

There are two types of cluster tests that can be requested on a PR:
- A basic cluster test, which deploys a scratch cluster, and runs the test suite against it.
- A Hard Migration test, which tests the full hard migration workflow on a scratch cluster.

To request a cluster test to be run on your PR, comment on your pr `/cluster_test` or `/hdm_test`
for a basic test or a hard-migration test respectively. After commenting, reach out to the
[Splice Contributors](CONTRIBUTORS.md) to approve and trigger the actual test on your behalf.

### Enabling the new Canton bft ordering layer

If you want to run the integration tests with the new Canton bft, you can do so by including the message `[bft]` in your commit message.

## Running Tests Locally

### Managing Canton for Tests

To speed up our tests run against a long-running Canton instance.
To start the instance run `./start-canton.sh` for backend test and `./start-canton.sh -m` for frontend test.
It can be stopped via `./stop-canton.sh`.

There are 3 tmux windows open in the tmux session for Canton in wallclock time, Canton in simtime and
toxyproxy. You can switch between those with `Ctrl-b w`.

You should only need to restart it if you change
`apps/app/src/test/resources/simple-topology-canton.conf`. If you
encounter an error like the following, there might have been a problem
with the running Canton instance so try restarting.

```
ERROR c.d.n.e.SpliceLedgerConnection$$anon$1:WalletIntegrationTest/DSO=dso-app - Failed to instantiate ledger client due to connection failure, exiting...
```

#### Issues on macOS

In case you run into an issue with tmux on macOS and tmux-256color terminfo (unknown terminal "tmux-256color"),
put this command into ~/.tmux.conf or ~/.config/tmux/tmux.conf (for version 3.1 and later):

```
set-option default-terminal "screen-256color"
```

This is sufficient for most cases. If you insist on using `tmux-256color` instead of switching to `screen-256color`,
you will need to install ncurses and setup terminfo following the instructions [here](https://gist.github.com/bbqtd/a4ac060d6f6b9ea6fe3aabe735aa9d95).

Another issue that you can experience on macOS is tmux being unresponsive (unable to switch windows) in the Terminal
app (default). Then, you may need to switch to iTerm, for example.

#### Using a local build of Canton

When debugging and fixing issues in Canton itself, it can be useful to run a local build of Canton.
You can do so as follows:
1. Checkout `https://github.com/DACH-NY/canton` and follow its `contributing/README.md` to get it to build.
2. Call `sbt bundle` to build a Canton enterprise release in `<YOUR_CANTON_REPO>/enterprise/app/target/release`.
3. Call `start-canton.sh -c <YOUR_CANTON_REPO>/enterprise/app/target/release/canton-enterprise-<VERSION>-SNAPSHOT/bin/canton`

### Frontend Testing

Frontend integration tests are either run with _sbt_ against local canton and Splice instances from the repository root directory using:
- `./start-canton.sh` to start canton,
- `./scripts/start-backends-for-local-frontend-testing.sh` to start the Splice backends,
- `./start-frontends.sh` to start the UIs,
- `sbt testOnly *FrontendIntegrationTest*` to run all Frontend tests, or a more specific selection to run
  only specific tests (see [SBT commands](DEVELOPMENT.md#sbt-commands)).
- When done, run `./stop-canton.sh` and `./stop-frontends.sh`.

or done with _vitest_ against mock data from the corresponding UI frontend directory using:
- `npm test` to run the tests,
- `npm run dev` to start the frontend and navigate the UI against mock handlers.

### Manual Frontend Testing

Similarly to running automated frontend tests, you can spin up the frontends and backends for interacting manually
with the frontends locally.

you first need to start Canton and the Splice apps. Here we use the topology from our tests:

1. Start Canton with minimal topology for front-end test.
```
./start-canton.sh -m
```

2. Start the Splice apps and run the bootstrap script to
   initialize. This starts the necessary apps (in a single process) to run the front ends.
   The logs from these apps are output to `log/splice-node_local_frontend_testing.clog`.

```
./scripts/start-backends-for-local-frontend-testing.sh
```

Note you can add the flag ``-s`` to skip ``sbt --batch bundle``.

3. To build and start the frontends, type:
```
./start-frontends.sh
```

Once this is complete, the front ends will be running on the ports on localhost as follows:

3<frontend><user>, where:

- <frontend> is as follows:
  - 0 for wallet
  - 1 for directory
  - 2 for sv UI
  - 3 for scan
  - 4 for splitwell
- <user> is as follows:
  - 00 for alice
  - 01 for bob
  - 02 for charlie
  - 11 for sv1
  - 12 for sv2
  - 20 for splitwell

See the start-frontends.sh script for the full explicit list of frontends and their port numbers.

For the UI's running as Alice and Bob, you can login as the
`alice_wallet_user` and `bob_wallet_user` users respectively.

Note that `start-frontents.sh` serves the different frontends from separate tmux screens,
and then attaches the terminal to that tmux session. To detach from tmux, type `Ctrl+B D`.
To switch between screens, type `Ctrl+B <screen>`.

### Running and Debugging Integration Tests

The integration tests are located at [`/apps/app/src/test/scala/org/lfdecentralizedtrust/splice/integration/tests/`](/apps/app/src/test/scala/org/lfdecentralizedtrust/splice/integration/tests).
They work by defining and starting a full network topology and running Canton console commands against that topology,
see for example the [`DirectoryIntegrationTest.scala`](/apps/app/src/test/scala/org/lfdecentralizedtrust/splice/integration/tests/DirectoryIntegrationTest.scala).
Also see the Scaladocs on Canton's [`BaseIntegrationTest.scala`](/canton/community/app/src/test/scala/com/digitalasset/canton/integration/BaseIntegrationTest.scala) for more information about the intended usage of the test framework.

Many tests use the topology and base configuration defined in [`/apps/app/src/test/resources/simple-topology.conf`](apps/app/src/test/resources/simple-topology.conf), or a variant thereof.
Adjusting these configurations can sometimes help with debugging.
See for example https://docs.daml.com/canton/usermanual/monitoring.html on how to adjust logging and monitoring for Canton nodes.

Frontend tests use selenium for launching a (usually headless) browser (currently we use Firefox), and interacting with it as a user would.
To make it run with a UI, for debugging - turn the headless flag in [`FrontendIntegrationTest.scala`](/apps/app/src/test/scala/org/lfdecentralizedtrust/splice/integration/tests/FrontendIntegrationTest.scala) to false.
To take screenshots (also in headless mode) of the browser at certain points of the tests - call `screenshot()` from [`FrontendIntegrationTest.scala`](/apps/app/src/test/scala/org/lfdecentralizedtrust/splice/integration/tests/FrontendIntegrationTest.scala) in your test.

You can run integration tests from IntelliJ by navigating to the file and clicking the little green "run-triangle"
in the gutter at the start of the test definition.
You can also run them from `sbt` as explained in the section on `sbt` below.
The logs from test executions are output to `/log/canton_network_test.clog`.
Use `lnav` to view these logs for debugging failing test cases.
No installation of `lnav` is required, as it is provided by default by our `direnv`.

Documentation about common pitfalls when writing new integration tests and debugging existing ones can be found [here](/apps/app/src/test/scala/org/lfdecentralizedtrust/splice/integration/tests/README.md).
If you wish to extend our testing topology please also consult [this README](/apps/app/src/test/resources/README.md) about name and port allocation.

### Enabling the new Canton bft ordering layer

If you want to run the integration tests locally with the new Canton bft, canton must be started with the `-e` flag.
This can be done by running `./start-canton.sh -we`.
Furthermore the integration test must run with the `SPLICE_USE_BFT_SEQUENCER` environment variable set to `true`.
Eg of test run:

```bash
 SPLICE_USE_BFT_SEQUENCER=1 sbt 'apps-app/ testOnly org.lfdecentralizedtrust.splice.integration.tests.SvDevNetReonboardingIntegrationTest'
```

### Testing App Behaviour Outside of Tests Without Running Bundle

Sometimes, you may need to debug startup behaviour of the Splice apps that is causing issues for the
initialization of the [`SpliceEnvironment`](apps/app/src/main/scala/org/lfdecentralizedtrust/splice/environment/SpliceEnvironment.scala).
You usually can't debug this behaviour
via our integration tests because the integration tests require an initialized `SpliceEnvironment`.
At other times, you may want to start an interactive console without having to run `sbt bundle`.

You can achieve this by using the ['Simple topology' runtime configuration](https://i.imgur.com/dPgUd2Q.png) from IntelliJ.
After starting it, a `Run` window with an interactive console should open: [console](https://i.imgur.com/zQfbVvs.png).
Using the runtime configuration, you can also set breakpoints as you could when executing a test from Intellij and
see the results of adding log statements without needing to run `sbt bundle`.

All screenshots are from IntelliJ IDEA 2020.1.4 on Ubuntu.

If you don't use IntellIJ, a workaround is running `sbt apps-app/runMain org.lfdecentralizedtrust.splice.SpliceApp -c <conf-files>`, however,
this doesn't give you a debugger.

### Testing Auth0 Auth Flows Locally

(This section assumes access to the test auth0 domains)

If you want to run one of the integration tests with a
`LocalAuth0Test` tag, you will need to pass Auth0 management API
credentials for our `canton-network-test` tenant to `sbt`. This is
done using environment variables that are most easily maintained in
`.envrc.private`. Instructions on how to populate that file are
[here](DEVELOPMENT.md#private-environment-variables).

```
export AUTH0_TESTS_MANAGEMENT_API_CLIENT_ID=…
export AUTH0_TESTS_MANAGEMENT_API_CLIENT_SECRET=…
```

Note that [Running The Preflight Check](#running-the-preflight-check)
also requires Auth0 management API credentials, but for a different
tenant. Following the linked instructions above will provide
definitions in `.envrc.private`.

```
export AUTH0_CN_MANAGEMENT_API_CLIENT_ID=…
export AUTH0_CN_MANAGEMENT_API_CLIENT_SECRET=…
```

### Running The Preflight Check

(This section assumes direct access to the test clusters)

The preflight check runs an integration test where a local validator
connects to a Splice cluster. To run the check against a
cluster (see section `GCE Clusters`), change into the cluster's
deployment directory, and run `cncluster preflight`:

```
cd cluster/deployment/<cluster name>
cncluster preflight
```

Note:
- The preflight check will fail if your branch is sufficiently divergent from the main branch (in particular, if you made any changes to the Daml model).

You can also launch an SBT shell that is configured to run the
preflight checks. This is useful if you want to iterate more quickly
on the preflight checks or filter them down to only a subset of the
preflight check:

```
cd cluster/deployment/<cluster name>
cncluster sbt_for_preflight
sbt:amulet> testOnly *Preflight* -- -z validator1 # only run the tests against validator1
```

#### Configure Auth0 Environment

(This section assumes access to the test auth0 domains)

The preflight check also requires access to Auth0's management
API. This access is granted via credentials to the API Explorer
Application defined within of the Auth0 tenant, which are stored in
`.envrc.private` and populated as described
[here](DEVELOPMENT.md#private-environment-variables).

Be aware: these tokens allow the requester to perform any
administrative action against the Auth0 tenant! Use caution and keep
production values secure.

#### Configure SV Web UI Password

For testing that we can interact with the web UIs of our own SVs (sv1-4), the preflight check needs to know about the passwords for logging in to those UIs.
At the moment all 4 SVs share the same password, which needs to be configured via the `SV_DEV_NET_WEB_UI_PASSWORD` environment variable.
Contact the Maintainers for the currently used password.

### Setting up `lnav` to Inspect Canton and CometBFT logs

If you have never used `lnav` to inspect Canton or CometBFT logs, then we recommend:
1. Call `lnav --help` to determine the configuration directory. It should be something like `~/.lnav` or `~/.config/lnav`.
2. Set `export LNAV_CONFIG_DIR=<the directory output by the help text above>`
3. Create the following symlinks to automatically keep the format definitions up to date:
   ```
   ln -sf $PWD/canton/canton-json.lnav.json $LNAV_CONFIG_DIR/formats/installed/canton_logstash_json.json
   ln -sf $PWD/support/cometbft-json.lnav.json $LNAV_CONFIG_DIR/formats/installed/cometbft-json.json
   ```
4. Type `lnav log/canton_network_test.clog` to inspect the test logs.
5. Take the time to familiarize yourself with docs for the `lnav` [UI](https://docs.lnav.org/en/latest/ui.html#ui)
   and [HotKeys](https://docs.lnav.org/en/latest/hotkeys.html), and learn to effectively navigate the test logs.
   The Canton docs also contain a [short tutorial](https://docs.daml.com/canton/usermanual/monitoring.html#viewing-logs) highlighting the most relevant features and hotkeys.
6. In addition to the above documentation, here are some Splice specific tips:
   1. Some of our debug log messages contain a lot of data including newlines and can take up quite a bit of vertical space.
      Use `:filter-out` to remove verbose loggers, for instance logging for incoming and outgoing network requests: `:filter-out RequestLogger`.
   2. Most (but not all!) log messages are tagged with the ID of the configuration (the config ID is appended to the logger name).
      Since each test uses a random configuration ID, you can use `:filter-in config=9463beca` to only keep log messages generated by
      the test with configuration ID `9463beca`.

### Handling Errors in Integration Tests

Generally, errors in integration tests should be handled through using Canton's `com.digitalasset.canton.logging.SuppressingLogger`.
The suppressing logger allows you to, e.g., specify a warning you expect to see and then ensures that it is isn't emitted
as a warning to the log.
If it would be emitted as a warning to a log, CI would fail as we ensure via `check-logs.sh` (or analogue: `sbt checkErrors`)
and `check-sbt-output.sh` that no unexpected warnings or errors that our integration tests log no unexpected warnings
or errors.

The easiest way to how to use `SuppressingLogger` is by looking at existing usages of its methods.
If you don't find an usage of a given method within the Splice repo, you can look for usages in the Canton repo.

### Connecting external tools to the shared Canton instances

Our shared Canton participants (the ones started with`./start-canton.sh`) use an authenticated ledger API.
If you have an external tool that needs to access one of the participants using the ledger API,
you will need to configure the app to supply a valid JWT token to each request.

If the tool can be configured to use a static token, generate one on https://jwt.io with the following payload
```
{
  "sub": "<ledger api user name>",
  "aud": "https://canton.network.global",
}
```
and use HS256 as the signing algorithm with the HMAC secret set to "test".

If the tool can be configured to fetch tokens from an OAuth2 server using client credentials,
run
```
./scripts/test-oauth-server.sh
```
and point your tool to the displayed URL.
Set the client id to the desired ledger API user name, and use an arbitrary value for the client secret.

### Testing App Upgrades

Upgrades of the Splice apps are tested automatically in CI using AppUpgradeIntegrationTest.

- Every PR is tested for upgrade from the commit in `main` from which it branched
- Every commit to `main` is tested for upgrade from the previous commit

PRs/commits that include `[breaking]` in their commit message, or that bump the Canton binary are excluded from this test.

The test spins up a full network in the source version, creates some activity, then gradually upgrades several of the components (SVs and validators)
one-by-one to the current commit's version.

## Deployment Tests

Static deployment tests are run on every commit to `main` and on every PR tagged with `[static]` or `[ci]`.
They guard against unintended changes to deployed state resulting from changes to Helm charts and Pulumi deployment scripts.
The tests described here are **not a replacement for testing via cluster deployment**.
They are meant to provide a quick feedback loop and to offer additional protection against regressions for code paths that are not sufficiently well covered by automatic cluster tests.

### Helm checks

We use [helm-unittest](https://github.com/helm-unittest/helm-unittest/) for some of our Helm charts.
To run all Helm chart tests locally run `make cluster/helm/test`.
To run only the tests for a specific chart `CHART`, run `helm unittest cluster/helm/CHART`.
If this produces an error: "### Error:  Chart.yaml file is missing", please run `make cluster/helm/build`.

Refer to the documentation of `helm-unittest` for more information on how to extend our Helm tests.
When writing or debugging Helm tests, it is often useful to run `helm unittest` with the `-d` flag.
This produces rendered yaml files under a local `.debug` folder
that can be inspected to understand errors or determine the correct paths for assertions.

### Pulumi checks

Our pulumi checks are based on checked in `expected` files that need to be updated whenever the expected deployment state changes.

Please run `make cluster/pulumi/update-expected` whenever you intend to change Pulumi deployment scripts in a way that alters deployed state.
Compare the diff of the resulting `expected` files to confirm that the changes are as intended.
