# Table of Contents

1. [Setting up Your Development Environment](#setting-up-your-development-environment)
    1. [Directory Layout](#directory-layout)
    1. [IntelliJ Setup](#intellij-setup)
    1. [Using `sbt`](#using-sbt)
1. [Contributing Changes](#contributing-changes)
    1. [Contributing as a New Joiner](#contributing-as-a-new-joiner)
    1. [Contribution Guide](#contribution-guide)
    1. [Unused Import Warnings](#unused-import-warnings)
    1. [TODO Comments](#todo-comments)
    1. [Daml Numerics](#daml-numerics)
    1. [Protobuf and GRPC Guidelines](#protobuf-and-grpc-guidelines)
    1. [Editing Daml](#editing-daml)
    1. [Bumping Our Canton fork](#bumping-our-canton-fork)
    1. [Code Layout](#code-layout)
    1. [Domain Specific Naming](#domain-specific-naming)
    1. [App Architecture - Initialization](#app-architecture---initialization)
    1. [Frontend Code](#frontend-code)
    1. [Conversions between Java & Scala types](#java-conversions)
1. [Testing](#testing)
    1. [Managing Canton for Tests](#managing-canton-for-tests)
    1. [Managing Frontends for Tests](#managing-frontends-for-tests)
    1. [Running and Debugging Integration Tests](#running-and-debugging-integration-tests)
    1. [Testing App Behaviour Outside of Tests Without Running Bundle ](#testing-app-behaviour-outside-of-tests-without-running-bundle)
    1. [Running The Preflight Check](#running-the-preflight-check)
1. [Building and Running the Wallet and Splitwise Apps](#building-and-running-the-wallet-and-splitwise-apps)
    1. [Building the Wallet and Splitwise Frontend](#building-the-wallet-and-splitwise-frontend)
    1. [Running the Wallet and Splitwise Frontend](#running-the-wallet-and-splitwise-frontend)

For information on the runtime configuration of a Canton Network
cluster, please see the [cluster specific documentation](/cluster/README.md).

## Setting up Your Development Environment

1. Install [direnv](https://direnv.net/#basic-installation).
2. Install Nix by running: `bash <(curl -sSfL https://nixos.org/nix/install)`
3. After switching to the CC repo you should see a line like
```
direnv: error /home/moritz/daml-projects/canton-coin/.envrc is blocked. Run `direnv allow` to approve its content
```
4. Run `direnv allow`. You should see a bunch of output including `direnv: using nix`.

**Important:** start your IDE and other development tools from a console that
has this `direnv` loaded; and thus has the proper version of all the
project dependencies on its `PATH`.

If you encounter issues, try exiting and reentering the directory to reactivate direnv.

5. On MacOS, please install the following globally:
   1. Envoy, by running: `brew update ; brew install envoy`
   2. Firefox, by following the process here: <https://www.firefox.com>

### Directory Layout

See the `README.md` files in the top-level directories for details.

Most top-level directories logically correspond to independent repositories,
which are currently maintained within this one repository for increased
delivery velocity using head-based development.

We expect to start splitting off repositories as part of the work to deliver
M3 - TestNet Launch.

### IntelliJ Setup

* Clone the repository first, if you haven't yet
* Run `which java` and `which sbt` within `the-real-canton-coin` directory to respectively find the JRE/SDK
    and sbt versions used by nix. The outputs should roughly look as follows:
    ```
    the-real-canton-coin$ which java
    /nix/store/rqii97havwmrzan6wk1lbh5nc48w821y-openjdk-11.0.15+10/bin/java
    the-real-canton-coin$ which sbt
    /nix/store/9q28hwzz8yy75l317k2v2mdq485hgja0-sbt-1.6.2/bin/sbt
    ```
* Add the Java SDK from nix per the instructions [in the Daml repo.](https://github.com/digital-asset/daml/blob/main/BAZEL.md#configuring-the-jdk-in-intellij)
 In the example above, the JDK would be at `/nix/store/rqii97havwmrzan6wk1lbh5nc48w821y-openjdk-11.0.15+10/lib/openjdk`.
* Open the repository via 'File -> New -> Project from existing sources'
* 'Import project from external model' and select sbt
* Point IntelliJ to the JRE home and sbt-launch jar. From the example above, these should respectively be
        `/nix/store/rqii97havwmrzan6wk1lbh5nc48w821y-openjdk-11.0.15+10/` and
        `/nix/store/9q28hwzz8yy75l317k2v2mdq485hgja0-sbt-1.6.2/share/sbt/bin/sbt-launch.jar`.
* Also configure it with the JDK you added above. If the JDK doesn't show up as one of the options, you may need to
    choose any other SDK and set up the correct SDK for the project after following the rest of the steps.
    In that case, you will need to verify that all usages of the JDK in 'Settings' and 'Project Structure' use the correct
    SDK.
* Otherwise choose [these settings in the dialogue](https://i.imgur.com/B3yWCZ9.png) (see sbt explanations [here](https://www.jetbrains.com/help/idea/sbt.html))

You should then see a 'sbt shell' window in IntelliJ that allows you to build and test the Scala code while using the
same package references as nix. If IntelliJ asks you at the end if you want to overwrite any previous `.idea/*` files, say yes.

### Using `sbt`

#### `sbt` settings
Make sure to configure the JVM heap size to at least 4G when using IntelliJ. In particular:
- In Intellij, under Settings, search for "sbt" and then under JRE add `-Xmx4G -Xms2G` to VM Parameters.
- In Intellij in the same menu, set the maximum heap size to at least 4000M.

#### `sbt` Commands

This Section gives an overview of common sbt commands.
More commands can be found in build.sbt and BuildCommon.scala.

- `clean`: deletes all generated files (in the target directory)
- `clean-cn`: like clean but only for our own apps not for the Canton fork
- `compile`: compile production code (excluding test code)
- `Test/compile`: compile production and test code
- `apps-common/compile`: compile production code of the `apps-common` subproject
- `apps-frontends/compile`: compile and codegen only frontend code
- `apps-frontends/npmLint`: checks formatting of frontend code, but does not fix anything
- `apps-frontends/npmFix`: fixes formatting of frontend code
- `scalafixAll`: invoke scalafix across all configurations where scalafix is enabled.
    `scalafix` is a linting and rewrite tool we use to organize imports. This may run for a long time as it needs to do a full compile.
- `format`: apply `scalafmt` to format source files
- `formatFix`: apply `scalafmt`, `sbt scalafixAll` and `sbt apps-frontends/npmFix` to format source files
- `lint`: lint-check. Does not apply any fixes. Checks enforcement of `scalafmt`, `buf`, `scalafix` and `apps-frontends/npmLint` rules
- `damlBuild`: create `.dar` files for all Daml projects
- `protobufLint`: to lint our protobuf files using `buf`
- `bundle`: create a release bundle in `apps/app/target/release/<version>`. The release binary is loaded into your PATH automatically via `direnv`. Simply run `coin` to call it.
- `checkErrors`: check test log for errors and fail if there is one. Note that if you haven't deleted your local log file in a long time, this may find very old errors.

Test:
- `testOnly myWildcard`: runs all tests matching wildcard, e.g.,
  `testOnly com.digitalasset.myPackage.*` runs all tests in package `com.digitalasset.myPackage`.
  `testOnly *Wallet* -- -z "allow calling tap"` runs all tests with classname matching `*Wallet*` and test description matching `allow calling tap`.
- `test`: runs all tests (with the exception of some tests running against the cluster that are excluded on purpose - see the overwrite of `test` in `BuildCommon.scala` for more details)
- `damlTest`: run the Daml script tests included with the apps' Daml files

## Contributing Changes

### Contributing as a New Joiner
For your first issue, you can take a look at [issues labelled with the `starter` tag](https://github.com/DACH-NY/the-real-canton-coin/issues?q=is%3Aissue+label%3Astarter). Else, please ask your onboarding
buddy for help with getting started on the code base.

### Contribution Guide

We share a lot of our tooling and processes with Canton.
We thus use [Canton's Contributing Guide](https://github.com/DACH-NY/canton/blob/main/contributing/README.md)
as a baseline, and list below only the points where we differ.

Please read the above guide in particular to learn about metrics, logging, tracing, Scala guidelines, Protobuf guidelines, TODO notes, formatting and git hooks.

Committed code must adhere to our formatting guidelines. To automatically reformat all code, run `sbt formatFix`. 

### Unused Import Warnings

If the unused import, local variable or implicits warnings get in the way during development, you can locally turn them into
an info summary that just displays the number of warnings by creating the file `.disable-unused-warnings` and
calling `sbt reload`. Note that this requires a partial re-compile.

### TODO Comments

Call `./scripts/check-todos.sh` to extract a list of all TODO comments in the project,
and get a list of TODO comments that do not conform [Canton's TODO style guidelines](https://github.com/DACH-NY/canton/blob/main/contributing/README.md#todo-comments).
Note that in contrast to these guidelines, we
- disallow `FIXME` comments on `main`
- use a double-number milestone format, e.g. `M1-92`
- ignore `.. todo::` comments in .rst files
- use normal .rst comments, e.g., `.. TODO(M1-14): some left-over doc work`

Note that these guidelines are enforced in CI.

### Daml Numerics

To represent Daml `Numeric`s for any user facing APIs (console commands), we use `scala.math.BigDecimal`s.
We use Scala BigDecimals instead of Java BigDecimals (that are used in the Daml repo) because
integers, floats etc. are automatically converted
to Scala BigDecimals by the Scala compiler unlike Java BigDecimals
(`wallet.tap(10)` vs `wallet.tap(new java.math.BigDecimal(10))`).

To represent Daml Numerics in Protobuf we use `string`s (there is no Protobuf BigDecimal type). Conversions to
and from `string`s should occur via `com.daml.network.util.Proto.encode/tryDecode`.

When interacting with the Ledger API, we convert the Scala BigDecimals to Java BigDecimals.

Overall, please refer to the `wallet.tap` command implementation for the canonical handling of Daml Numerics.

### Protobuf and GRPC Guidelines

Generally endeavor to model your [GRPC and Protobuf definitions using Google's guidelines](https://cloud.google.com/apis/design/naming_convention).
We use [`buf`](https://docs.buf.build/tour/lint-your-api)'s `DEFAULT` linting rules to enforce many of these guidelines in CI using the `protobufLint` `sbt` task.
See [their documentation](https://docs.buf.build/lint/rules#default) for what these are and why they are beneficial.

Below we list additional rules specific to our project:

### Editing Daml

We use separate Daml projects for separate apps (e.g. CC, the Wallet, and the Directory).
You can find these projects in the `daml/` subfolders of the respective apps.
These Daml projects define the on-ledger API used to synchronize different parties in the apps' workflows.

We build and test these projects using the `sbt` commands `damlBuild` and `damlTest`.
The implementation of the `sbt` plugin can be found in [`/project/DamlPlugin.scala`](/project/DamlPlugin.scala).

To edit the files in a particular Daml project, for example, `/apps/wallet/daml`, proceed as follows:

1. Start `sbt` in the repo root to get access to an `sbt` shell (or use the one in IntelliJ).
2. Start `damlBuild` in your `sbt` shell to build the .dars for all Daml projects.
3. Start `daml studio` in the repo root, which starts VS code.
4. Open and edit the .daml files in `/apps/wallet/daml` in VS code. You should see them being typechecked on the fly.
5. See `/apps/wallet/daml/daml.yaml` for the .dar dependencies of `/apps/wallet/daml`.
   If you change any of them, then you can propagate these changes across the .dars as follows:
   1. redo Step 2
    3. use Ctrl-Shift-P "Developer: Reload Window" in VS code to restart the `daml studio` language server with the updated package dependencies.

*Tip:* if `damlBuild` fails with weird errors, then that might be due to stale `damlBuild` outputs.
Try forcing a clean rebuild by cleaning via SBT, e.g., `apps-common/clean` and similar for the dependent project.

### Bumping Our Canton fork

Current Canton commit: `c6d8cfd13267547a23c542d2c4c45e53ec2b8459`

1. Check out the [Canton Open Source repo](https://github.com/digital-asset/canton) at the current Canton commit listed above.
   NOTE: if you can't find the commit, then you are probably using the closed source https://github.com/DACH-NY/canton repo.
   That won't work. You need the Canton OSS repo linked above.
2. Create a branch named `canton-bump-<sprintnr>`
3. Define the environment variable used in the commands below using `export PATH_TO_CANTON_OSS=<your-canton-oss-repo-path>`
4. Create a Canton patch file capturing all our changes relative to that `./scripts/diff-canton.sh $PATH_TO_CANTON_OSS/ > canton.patch`
5. Undo our changes: `git apply '--exclude=canton/community/app/src/test/resources/examples/*' --directory=canton -R canton.patch`
   The exclusion is because those files are under a symlink and we don’t want to change them twice.
6. Create a commit to ease review, `git add canton/ && git commit -m"Undo our changes"`
7. Checkout `main` in the Canton OSS repo and check that this is indeed a newer commit than the one we are currently using.
8. Copy the Canton changes: `./scripts/copy-canton.sh $PATH_TO_CANTON_OSS`
9. Create a commit to ease review, `git add canton/ && git commit -m"Bump Canton commit"`
10. Reapply our changes `git apply '--exclude=canton/community/app/src/test/resources/examples/*' --directory=canton --reject canton.patch`
    and resolve conflicts (if any).
11. Create a commit to rease review `git add canton/ && git commit -m"Reapply our changes"`
12. Learn Canton's SDK version from `head -n15 $PATH_TO_CANTON_OSS/project/project/DamlVersions.scala`
13. Bump the SDK/Canton versions in the following places:
    1. The current Canton commit in this `README.md`
    2. `version` in `CantonDependencies`
    3. In `nix/canton.nix`:
       1. Bump `version` to the desired canton version. You can find
          that version by looking at the corresponding SDK
          release. E.g.,
          https://github.com/digital-asset/daml/releases/tag/v2.5.0-snapshot.20221010.10736.0.2f453a14
          lists `canton-open-source-20221011.tar.gz` under the
          artifacts so `20221011` is the Canton version.
       2. Bump `sdk_version` to the associated sdk snapshot version
       3. Adjust the `sha256` digest by copying back the new hash when Nix throws an error during validation (change
             a digit of the hash to make it fail and then `cd ..` and `cd -` out of the repo).
14. Bump the sdk version in our own daml.yaml files via `./set-sdk.sh $sdkversion` to the same version.
15. Create another commit, `git add -A && git commit -m"Bump Canton commit and Canton/SDK versions"`
16. Make a PR with your changes, so CI starts churning.
17. Test whether things compile using `sbt Test/compile`.
    In case of problems, find the related change in the **closed source Canton repo** and use the change and its commit message to adjust our code.
    If there are any, remove all `*.rej` files.

You can refer to https://github.com/DACH-NY/the-real-canton-coin/pull/446/commits for an example of how the update PR should look like.


#### Message Definitions

* All Protobuf definitions should be using [`proto3`](https://developers.google.com/protocol-buffers/docs/proto3)
* Avoid wrapping primitive types in a message structure unless future extensibility will likely be required
* Use a plural name for `repeated` fields
* Use `string` fields with a suffix `contract_id` to store contract ids
* Use `string` fields with a suffix `party_id` to store party ids

### Code Layout

* Ensure that the generated stubs will be in their own package separate from any implementations
* Prefer java/maven like structuring for the package and directory layout
* Place `.proto` files in `src/main/protobuf`
* Prefer having a single `.proto` definition per service.
* Refer to generated Protobuf classes with a package prefix, e.g., `v0.MyMessage` instead of `MyMessage`.
  This avoids name conflicts with our hand-written classes.

### Domain Specific Naming

* Use `listXXX`, `acceptXXX`, `rejectXXX`, `withdrawXXX` for managing proposals, requests etc.
* [Beware of the differences](https://www.bkacontent.com/gs-commonly-confused-words-amount-number-and-quantity)
  between `quantity`, `amount` and `number`: **`quantity`**  is usually the right choice
* Between `sender`/`receiver` and `payer`/`payee`: please use **`sender`/`receiver`**

### App Architecture - Initialization

#### SVC App

The SVC app is special since it creates the SVC party which eventually
needs to be decentralized and because other apps rely on the SVC app
being up so they can query the SVC party identity.

Startup proceeds as follows:

1. Allocate SVC party and user
2. Upload DAR file
3. Create initial coin rules
4. Start automation
5. Start gRPC service

#### Validator App

The validator app is the only application with admin claims and is
responsible for allocating parties/users and uploading DARs for all
other apps including the wallet. The wallet user and the DARs and
users for other applications are specified in the static config of the
validator app.

The user of the validator app itself is allocated outside of the app
by the validator operator. This is required because the validator app
does not have authorization to allocate its own user.

Startup proceeds as follows:

1. Wait for the validator user to be created and query the primary party.
2. Create users and their primary parties for the wallet and all other applications
   specified in the config provided these users and their parties have not already been created.
3. Upload DARs for wallet and all other applications.
4. Perform one-time initialization, e.g., create validator right for
   the validator party itself
5. Launch gRPC service

#### All Other Applications

All other applications have one service user and do not have admin claims.

Startup proceeds as follows:

1. Wait for the user to be created and query the primary party.
2. Wait for the DARs to be uploaded
3. Run one-time initialization, e.g., create certain contracts.
4. Start automation
5. Start gRPC service

### Frontend Code

#### Background

This section discusses how to contribute, or add new frontend code for an app. To understand how to run frontends locally, see [Building and Running the Wallet and Splitwise Apps](#building-and-running-the-wallet-and-splitwise-apps).

Frontend code projects are managed via [`npm workspaces`](https://docs.npmjs.com/cli/v8/using-npm/workspaces). This gives us a way to manage multiple distinct NPM packages all co-located in the same monorepo, and confers several benefits:

- One local monorepo package can be installed as a dependency of another, enabling "easy" code-sharing.
- With `npm install`, all dependencies of all workspace projects are installed in the root `node_modules` folders, giving us de-deduplication.
- If all workspace projects share common scripts, you can easily run that script across all workspaces in one command.
- All required `npm` commands are issued from `sbt compile`, so there should not be a need to run e.g. `npm install` directly. 

#### New Packages

In this section only, the term "root-level directory" will describe the workspace root, which is inside `apps/` (**not** the _repo_ root directory).

If you want to add a new package to the workspace, first register its directory in the root-level `apps/package.json` workspaces key. The directory referenced here must contain a `package.json` of its own defining the workspace package itself -- name, dependencies, etc.

Then add the new package to `build.sbt` following the examples from the existing frontend packages.

Running `sbt compile` (or manually `npm install` from the root) installs the dependencies of all registered workspace packages.

Make sure your package contains at least the scripts `build`, `fix`, `check`, and `start`. This enables the use of (e.g.) `npm run build --workspaces` to run the build script for all packages in the workspace at once, as well as proper integration with `sbt`.

Your new package will need its own `tsconfig.json` file that inherits from the root tsconfig. See any existing workspace package for an example.

#### Common libs

In `apps/common/frontend` we have an NPM package containing common code. This package (named `common-frontend`) can be installed with `npm install common-frontend -w my-workspace-pkg`. You can import anything from it with `import { ... } from 'common-frontend'` in your package's source code.

You're also free to add more things in `common-frontend` to use across multiple frontend apps. This can really include anything: utility functions, reusable React components, shared config, etc. Just ensure whatever you add is exposed via the lib's entrypoint, `index.ts` (we use the [barreling](https://basarat.gitbook.io/typescript/main-1/barrel) technique to expose all modules from the root of the library).

There is also a package available named `common-protobuf`, located in `apps/common/frontend-protobuf`. This simply contains all the web-gRPC protobuf bindings for all our services in Typescript.

### Conversions between Java & Scala types

Because we use the Java bindings and codegen, we need to convert
between Java and Scala types, e.g., `Seq` and `java.util.List`.  We
try to use Scala types whereever possible so we delay the conversion
to Java types until the last possible point and convert from Scala to
Java as early as possible.

To convert, import `scala.jdk.CollectionConverters.*`. You can then use `toScala` and `toJava` methods.

## Testing

### Managing Canton for Tests

To speed up our tests run against a long-running Canton instance.
To start the instance run `./start-canton.sh`. It can be stopped via `./stop-canton.sh`.

You should only need to restart it if you change
`apps/app/src/test/resources/simple-topology-canton.conf`. If you
encounter an error like the following, there might have been a problem
with the running Canton instance so try restarting.

```
ERROR c.d.n.e.CoinLedgerConnection$$anon$1:WalletIntegrationTest/SVC=svc-app - Failed to instantiate ledger client due to connection failure, exiting...
```

### Managing Frontends for Tests

Some integration tests use our web frontends. Similarly to Canton described above, we also serve the frontends outside of the tests themselves.
To start serving the frontends run `./start-frontends.sh` (add `-d` if you prefer to avoid attaching the terminal to the npm server).
It can be stopped via `./stop-frontends.sh`.

### Running and Debugging Integration Tests

The integration tests are located at [`/apps/app/src/test/scala/com/daml/network/integration/tests/`](/apps/app/src/test/scala/com/daml/network/integration/tests).
They work by defining and starting a Canton network topology and running Canton console commands against that topology,
see for example the [`DirectoryIntegrationTest.scala`](/apps/app/src/test/scala/com/daml/network/integration/tests/DirectoryIntegrationTest.scala).
Also see the Scaladocs on Canton's [`BaseIntegrationTest.scala`](/canton/community/app/src/test/scala/com/digitalasset/canton/integration/BaseIntegrationTest.scala) for more information about the intended usage of the test framework.

Many tests use the topology and base configuration defined in [`/apps/app/src/test/resources/simple-topology.conf`](apps/app/src/test/resources/simple-topology.conf), or a variant thereof.
Adjusting these configurations can sometimes help with debugging.
See for example https://docs.daml.com/canton/usermanual/monitoring.html on how to adjust logging and monitoring for Canton nodes.

Frontend tests use selenium for launching a (usually headless) browser (currently we use Firefox), and interacting with it as a user would.
To make it run with a UI, for debugging - turn the headless flag in [`FrontendIntegrationTest.scala`](/apps/app/src/test/scala/com/daml/network/integration/tests/FrontendIntegrationTest.scala) to false.
To take screenshots (also in headless mode) of the browser at certain points of the tests - call `screenshot()` from [`FrontendIntegrationTest.scala`](/apps/app/src/test/scala/com/daml/network/integration/tests/FrontendIntegrationTest.scala) in your test.

You can run integration tests from IntelliJ by navigating to the file and clicking the little green "run-triangle"
in the gutter at the start of the test definition.
You can also run them from `sbt` as explained in the section on `sbt` below.
The logs from test executions are output to `/logs/canton_test.log`.
Use `lnav` to view these logs for debugging failing test cases.
No installation of `lnav` is required, as it is provided by default by our `direnv`.

### Testing App Behaviour Outside of Tests Without Running Bundle

Sometimes, you may need to debug startup behaviour of the Canton coin apps that is causing issues for the
initialization of the [`CoinEnvironment`](apps/app/src/main/scala/com/daml/network/environment/CoinEnvironment.scala).
You usually can't debug this behaviour
via our integration tests because the integration tests require an initialized `CoinEnvironment`.
At other times, you may want to start an interactive console without having to run `sbt bundle`.

You can achieve this by using the ['Simple topology' runtime configuration](https://i.imgur.com/dPgUd2Q.png) from IntelliJ.
After starting it, a `Run` window with an interactive console should open: [console](https://i.imgur.com/zQfbVvs.png).
Using the runtime configuration, you can also set breakpoints as you could when executing a test from Intellij and
see the results of adding log statements without needing to run `sbt bundle`.

All screenshots are from IntelliJ IDEA 2020.1.4 on Ubuntu.

If you don't use IntellIJ, a workaround is running `sbt apps-app/runMain com.daml.network.CoinApp -c <conf-files>`, however,
this doesn't give you a debugger.

### Running The Preflight Check

The preflight check runs an integration test where a local validator
connects to a global canton network. To run the check against a
cluster (see section `GCE Clusters`), change into the cluster's
deployment directory and run `cncluster preflight`:

```
cd cluster/deployment/devnet
cncluster preflight
```

Note that the preflight check will fail if you branch is sufficiently divergent from the main branch
(in particular, if you made any changes to the Daml model).

#### Setting up `lnav` to Inspect Canton logs

If you have never used `lnav` to inspect Canton logs, then we recommend:
1. Download the `canton.lnav.json` log format config file from https://github.com/DACH-NY/canton/blob/main/canton.lnav.json
2. Install the Canton log format using `lnav -i canton.lnav.json`, which will install it in `~/.lnav/formats/installed/canton_log.json` and enable it for auto-detection in future `lnav` sessions.
3. Type `lnav log/canton_test.log` to inspect the test logs.
4. Take the time to familiarize yourself with docs for the `lnav` [UI](https://docs.lnav.org/en/latest/ui.html#ui)
   and [HotKeys](https://docs.lnav.org/en/latest/hotkeys.html), and learn to effectively navigate the test logs.
   The Canton docs also contain a [short tutorial](https://docs.daml.com/canton/usermanual/monitoring.html#viewing-logs) highlighting the most relevant features and hotkeys.


#### Handling Errors in Integration Tests

Generally, errors in integration tests should be handled through using Canton's `com.digitalasset.canton.logging.SuppressingLogger`.
The suppressing logger allows you to, e.g., specify a warning you expect to see and then ensures that it is isn't emitted
as a warning to the log.
If it would be emitted as a warning to a log, CI would fail as we ensure via `check-logs.sh` (or analogue: `sbt checkErrors`)
and `check-sbt-output.sh` that no unexpected warnings or errors that our integration tests log no unexpected warnings
or errors.

The easiest way to how to use `SuppressingLogger` is by looking at existing usages of its methods.
If you don't find an usage of a given method within the CN network repo, you can look for usages in the Canton repo.

## Building and Running the Wallet and Splitwise Apps

### Building the Wallet and Splitwise Frontend

Run `sbt app-frontends/compile`, or the more general `sbt compile` to generate all auto-generated code required for the frontends 
(specifically, all ts code for our daml models and protobuf definitions), and build anything required for the frontends (e.g. install dependencies in `node_modules`).

### Running the Wallet and Splitwise Frontend

To test out the wallet frontend, you first need to start Canton and
our own apps. Here we use the topology from our tests:

1. Start Canton
```
./start-canton.sh

```

2. Start the Coin apps and run the bootstrap script to initialize.

We use a single bootstrap script to initialize both the wallets and the splitwise apps.
```
sbt "apps-app/runMain com.daml.network.CoinApp --config apps/app/src/test/resources/simple-topology.conf --bootstrap apps/splitwise/frontend/bootstrap.canton"
```

3. To build and start the frontends, type:
```
./start-frontends.sh
```
You can then browse to http://localhost:3000 and http://localhost:3001 for the two wallet UIs, and login as `alice_wallet_user` and `bob_wallet_user` respectively.

Note that start-frontents.sh serves the different frontends from separate tmux screens,
and then attaches the terminal to that tmux session. To detach from tmux, type `Ctrl+B D`.
To switch between screens, type `Ctrl+B <screen>`.

### NPM Lock file issues

CI enforces that the checked in lock files are up2date. However, for
the generated JS codegen, you might see issues where CI claims those
are not up2date.

The cause of this is a damlc issue where incremental builds do not
produce the same package id. This will be fixed in later versions.

To make sure your lock files match CI, run the following steps:

1. `find . -name '.daml' | xargs rm -r`
2. `sbt compile`
3. Check-in the updated lock file which should now match CI.
