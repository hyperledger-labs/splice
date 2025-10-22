# Table of Contents

- [Setting up your Development Environment](#setting-up-your-development-environment)
- [Setting up for building docker images](#setting-up-for-building-docker-images)
- [Private Environment Variables](#private-environment-variables)
- [Directory Layout](#directory-layout)
- [IDE Setup](#ide-setup)
  - [IntelliJ Setup](#intellij-setup)
  - [VSCode Setup](#vscode-setup)
    - [Configuring VS Code for Daml Development](#configuring-vs-code-for-daml-development)
    - [Configuring VS Code for Scala Development](#configuring-vs-code-for-scala-development)
- [Using `sbt`](#using-sbt)
  - [`sbt` Settings](#sbt-settings)
  - [`sbt` Commands](#sbt-commands)
  - [`sbt` Tips&Tricks](#sbt-tipstricks)
  - [Unused Import Warnings](#unused-import-warnings)
- [App Configuration Files](#app-configuration-files)
- [Editing Daml](#editing-daml)
- [Troubleshooting](#troubleshooting)
  - [Nix Issues on MacOS](#nix-issues-on-macos)
  - [NPM Lock file issues](#npm-lock-file-issues)

# Setting up Your Development Environment

1. Clone this repo.
1. Install [direnv](https://direnv.net/#basic-installation).
1. Install Nix by running: `bash <(curl -sSfL https://nixos.org/nix/install)`
1. Enable support for nix flakes and the nix command by adding to the
   following to your nix config (either `/etc/nix/nix.conf` if you
   have a multi-user install or `~/.config/nix/nix.conf`):
    ```
    extra-experimental-features = nix-command flakes
    ```
1. After switching to the Splice repo directory, you should see a line like
   ```
   direnv: error /home/moritz/daml-projects/canton-amulet/.envrc is blocked. Run `direnv allow` to approve its content
   ```
1. Run `direnv allow`. You should see a bunch of output including `direnv: using nix`.
1. (Optional) Configure artifactory credentials
   A few tests rely on Enterprise canton features. To be able to run those locally, you will require access to
   Digital Asset's enterprise artifactory. If you need to run those, please contact the Maintainers of this repo,
   per MAINTAINERS.md.
   Once you have access to artifactory, you can generate an artifactory Identity Token [here](https://digitalasset.jfrog.io/ui/admin/artifactory/user_profile).
   Your username is shown at the top of the page (under "User profile: XX").
   1. Add the following to `/etc/nix/netrc` (you might need to create that directory as root):
      ```
      machine digitalasset.jfrog.io
      login yourartifactoryusername
      password yourartifactoryidentitytoken
      ```
   1. In addition, add your artifactory user and password to `.envrc.private` file like so:
      ```
      export ARTIFACTORY_USER="yourartifactoryusername"
      export ARTIFACTORY_PASSWORD="yourartifactoryidentitytoken"
      ```
   Once added, reload direnv by typing `direnv reload` in your terminal.
1. (Optional) Configure artifactory credentials - troubleshooting
   If you defined Artifactory access, and are getting an authorization exception, like the following:
   ```
   direnv: using nix
   error: unable to download 'https://digitalasset.jfrog.io/artifactory/canton-enterprise/canton-enterprise-2.7.0-snapshot.20230614.10547.0.v03419b62.tar.gz': HTTP error 401 ('Unauthorized')
   ```
   1. Check that your access token is valid by running the following sample command:
      ```
      curl -vvv -L -u<yourartifactoryusername>:<yourartifactoryidentitytoken> "https://digitalasset.jfrog.io/artifactory/canton-enterprise/canton-enterprise-2.7.0-snapshot.20230614.10547.0.v03419b62.tar.gz" -o canton-enterprise-2.7.0-snapshot.20230614.10547.0.v03419b62.tar.gz
      ```
      If the download fails, check that your access token matches what is set in [Artifactory](https://digitalasset.jfrog.io/ui/admin/artifactory/user_profile).
      Also, check you have visability via the UI [here](https://digitalasset.jfrog.io/ui/repos/tree/General/canton-enterprise).
      If you don't have visibility via the UI then check with the repo Maintainers.
   1. If the artifact successfully downloaded, check the access rights of the file `/etc/nix/netrc`.
      If the access rights are more restrictive than `-rw-rw-r--`, update them:
      ```
      chmod 664 /etc/nix/netrc
      ```
      Note - `sudo` may be required to run the above command.
   1. Switch to the Splice repo directory.
   1. If the authorization exception isn't resolved, investigate further with additional logging
      by running the following command at the root of the repo:
      ```
      nix develop --debug --verbose path:nix

     **Important:** start your IDE and other development tools from a console that
     has this `direnv` loaded; and thus has the proper version of all the
     project dependencies on its `PATH`.

     If you encounter issues, try exiting and reentering the directory to reactivate direnv.

1. (optional) Enable [pre-commit](https://pre-commit.com/) to enforce format rules automatically:
    ```
    pre-commit install
    # or:
    pre-commit install -t pre-push
    ```
    _Note_: if you want to skip specific pre-commit hooks, add the hook ids to the `SKIP` variable separated by commas,
            e.g. `export SKIP=scalafmt` (see https://pre-commit.com/#temporarily-disabling-hooks).
            Also, you can bypass running pre-commit hooks altogether using the `--no-verify` / `-n` git commit option.


1. On MacOS, please install the following globally:
   1. Firefox, by following the process here: <https://www.firefox.com>

1. On MacOS, activate admin privileges using the lock icon (🔒 → 🔓) in the Dock, go to
    System Settings → General → AirDrop & Handoff, and disable AirPlay Receiver. Otherwise
    you will see on `start-canton.sh` runs
    ```
    Exception in thread "main" java.io.UncheckedIOException: Could not create Prometheus HTTP server
    Caused by: java.net.BindException: Address already in use
    ```


# Setting up for building docker images

If you are a Splice contributor (see CONTRIBUTORS.md), you may build developer Docker images locally
and upload them to the dev GHCR registry. In order to do so, you will need to follow the following first:

1. Initialize and update the runner container hooks
    submodule using `git submodule update --init .github/runners/runner-container-hooks`.
1. Configure Github Container Registry (GHCR) credentials
   The build pushes images to GHCR. You need to create a personal access token if you want to push images from your local machine.
   From your profile on github, go to Settings, Developer Settings, Personal Access Tokens, Tokens (classic), and create a new token with the `write:packages` scope.
   Best would be to add the environment variables to the `.envrc.private` file like the following:
   ```
   export GH_USER="yourgithubusername"
   export GH_TOKEN="yourgithubtoken"
   ```
1. Configure ScratchNet cluster locking.
    Open `./build-tools/cluster-lock-users.json`, and add a line of the format
    ```
    "<circleci-username>": ["<local-username>"],
    ```
    where `circleci-username` is the username you are using for logging into CircleCI
    (your GitHub username if you are logging into CircleCI using your GitHub account)
    and `local-username` is the local username on your machine (as returned by `whoami`).

    Open `./circleci/cluster-lock-slack-ids.json`, and add a line of the format
    ```
    "<local-username>": "<slack-user-id>",
    ```
    to receive slack pings when your cluster lock is ~1hr away from expiring. Determine your user ID
    from your profile settings, as described [here](https://www.workast.com/help/article/how-to-find-a-slack-user-id/).


# Private Environment Variables

There are a number of environment variables managed with `direnv` that
are used to contain private information. This includes credentials to
a range of external services, including Auth0 and Artifactory. To keep
this private information private, they are stored in a specific file
in the root of the project repository: `.envrc.private`. This file is
listed in `.gitignore` to prevent accidental commit to the repository.

Due to requirement to manually create and populate this file, there is
also checking to verify that the environment definitions expected to
be present in `.envrc.private` are in fact present. Missing
definitions will cause a warning to be reported when `.envrc` is
executed.

A list of expected environment definitions is as follows:

* Artifactory credentials
   * `ARTIFACTORY_USER`: your username at digitalasset.jfrog.io (can be seen in the top-right corner after logging in with Google SSO)
   * `ARTIFACTORY_PASSWORD`: Your identity token at digitalasset.jfrog.io (can be obtained by generating an identity token in your user profile)

If you are a Splice Contributor (see CONTRIBUTOR.md) and wish to push Docker images and
deploy to test clusters from your local machine, you will need also the following:

* Github credentials
   * `GH_USER`: your Github username
   * `GH_TOKEN`: your Github personal access token

* Auth0 Management API credentials (taken from the *API Explorer* application defined within each Auth0 tenant)
   * `AUTH0_CN_MANAGEMENT_API_CLIENT_ID`/`AUTH0_CN_MANAGEMENT_API_CLIENT_SECRET` - Auth0 [API Explorer](https://manage.auth0.com/dashboard/us/canton-network-dev/applications/ECfosW3sLHUfHatCRLEGUQ9YG9XMs9aq/settings) settings page. (Note `canton-network-dev` tenant.)
   * `AUTH0_SV_MANAGEMENT_API_CLIENT_ID`/`AUTH0_SV_MANAGEMENT_API_CLIENT_SECRET` - Auth0 [API Explorer](https://manage.auth0.com/dashboard/us/canton-network-sv-test/applications/OjD90OemoxGYTLqzmbSTDJlmCi6nbUnu/settings) settings page. (Note `canton-network-sv-test` tenant.)
   * `AUTH0_VALIDATOR_MANAGEMENT_API_CLIENT_ID`/`AUTH0_VALIDATOR_MANAGEMENT_API_CLIENT_SECRET` - Auth0 [API Explorer](https://manage.auth0.com/dashboard/us/canton-network-validator-test/applications/afx3XOvqCQlP43M3MTNJMwgrgKXzXlBV/settings) settings page. (Note `canton-network-validator-test` tenant.)
   * `AUTH0_TESTS_MANAGEMENT_API_CLIENT_ID`/`AUTH0_TESTS_MANAGEMENT_API_CLIENT_SECRET` Auth0 [API Explorer](https://manage.auth0.com/dashboard/us/canton-network-test/applications/DnmjyCAmMHWVD0yS0jZLgBv8pKL7dVMM/settings) settings page. (Note `canton-network-test` tenant.)

Be aware: The Auth0 tokens allow the requester to perform any
administrative action against the Auth0 tenant! Use caution and keep
these values secure.

# Directory Layout

See the `README.md` files in the top-level directories for details.

Most top-level directories logically correspond to independent repositories,
which are currently maintained within this one repository for increased
delivery velocity using head-based development. We expect to split this
repository into small ones in the future.

# IDE Setup

## IntelliJ Setup

* The following instructions work for recent IntelliJ installs (tested on "IntelliJ IDEA 2025.2.3 (Community Edition)").
* Install the Scala plugin in IntelliJ if you don't have it yet.
* Clone the repository first, if you haven't yet, and setup `direnv`
  (otherwise the environment variables referenced below are not defined).
* Run `jps` to make sure that there are no `sbt` instances running
* Run `sbt` from the **repository root** in a new terminal and leave it running. This will start and SBT server that
  IntelliJ will later connect to. Running it in the repository root makes sure that it has the right environment.
* Start IntelliJ from the **repository root directory** so that it has access to all the environment variables
  and the `nix` packages defined using `direnv` .
  On macos, this can be done via the `open` command, for example: `open -a "IntelliJ IDEA CE"`.
* Open the repository via "File -> Open".
* IntelliJ should prompt you whether you want to import the project through SBT or BSP. Choose BSP.
* If IntelliJ complains that no JDK is configured determine its path by running `echo $JAVA_HOME` in the repository root
  and add it to IntelliJ using the "Add JDK from disk" action (can be found using Ctrl-Shift-A or Command-Shift-A).
* After IntelliJ imports the project the setup should be complete. You can use the SBT shell started in previous steps
  for issuing build commands from the CLI.

## VSCode Setup

There are a few extensions that improve the VS Code experience when working on various parts of the Splice codebase, or for general collaboration. The recommendation is to pick the ones that currently seem relevant or useful to you.

**Recommended Extensions**:
- [GitLens](https://marketplace.visualstudio.com/items?itemName=eamodio.gitlens): Nice integration of git history/metadata inside the editor, displays git authors inline
- [Open in GitHub](https://marketplace.visualstudio.com/items?itemName=ziyasal.vscode-open-in-github): Makes it easy to share specific links to code via an in-editor context menu option
- [Docker](https://marketplace.visualstudio.com/items?itemName=ms-azuretools.vscode-docker)
- [Makefile Tools](https://marketplace.visualstudio.com/items?itemName=ms-vscode.makefile-tools)
- [OpenAPI Editor](https://marketplace.visualstudio.com/items?itemName=42Crunch.vscode-openapi)
- [YAML](https://marketplace.visualstudio.com/items?itemName=redhat.vscode-yaml)
- [Prettier](https://marketplace.visualstudio.com/items?itemName=esbenp.prettier-vscode): Code formatter for webdev-related stacks (Typescript/React/...)
- [Scala Syntax](https://marketplace.visualstudio.com/items?itemName=scala-lang.scala): Syntax highlighting for Scala files, only; use `Metals` for adding IDE-like functionality
- [Scala Metals](https://marketplace.visualstudio.com/items?itemName=scalameta.metals)
- [Run it On](https://marketplace.visualstudio.com/items?itemName=fsevenm.run-it-on): Optional, if you want to add an automatic "scalafix on save" command while editing Scala files
- [Nix](https://marketplace.visualstudio.com/items?itemName=bbenoist.Nix)
- [vscode-proto3](https://marketplace.visualstudio.com/items?itemName=zxh404.vscode-proto3)

### Configuring VS Code for Daml Development

- Install the Daml open source SDK by running
   ```
   ./build-tools/install-daml-sdk.sh
   ```
   or by following the instructions at https://docs.daml.com/getting-started/installation.html,
   while making sure that you install the version of the SDK referenced in `nix/canton-sources.json`.
   - The Daml SDK is not required for building our repository, but it's required for the Daml extension in VS Code.
- Install the Daml extension by running `daml studio` or by manually installing it from the VS Code extensions UI.

### Configuring VS Code for Scala Development

It is possible to use VS Code for Scala development via the Metals extension (see above), if you'd prefer not to use IntelliJ (or just want to try it out). Setting this up requires some similar configuration as IntelliJ:

* **Metals: Java Home**: From a terminal inside the repository directory & direnv environment, run `echo $JAVA_HOME` which should give you your Java Home path from nix: `/nix/store/<nix-pkg-id>...`. If not, try `which java` which should give you the nix path `/nix/store/<nix-pkg-id>.../bin/java`; strip the `/bin/java` suffix and use that path.
* Follow any VS Code prompts to restart bloop/metals or reload the window
* Ensure the following is in your VS Code `settings.json` (search for `Preferences: Open User Settings (JSON)` in the command palette):

```json
   "files.exclude": {
      "**/.bloop": true
   },
   "files.watcherExclude": {
      "**/.bloop": true,
      "**/.metals": true,
      "**/.ammonite": true
   },
```

* Optional: To run `scalafix` on a file, you can either use the `Metals: Run all scalafix rules` command from the command palette, or add the following to `settings.json` to run scalafix automatically every time you save:

```json
  "runItOn": {
    "commands": [
      {
        "match": ".*.scala",
        "isShellCommand": false,
        "cmd": "metals.scalafix-run"
      }
    ]
  },
```

This configuration requires the `Run it On` plugin to be installed.

**Caveats**
- I haven't tested the `sbt` integration, instead opting for running builds/tests/etc from the command line. Metals is still useful for providing type hints, syntax error catching, auto-complete, "go-to-definition", etc. In theory, you should be able to provide the path of the `sbt` binary vended by nix (via `which sbt`) to the `Sbt Script` option in the Metals settings.
- The cache can be finnicky: be prepared to have to re-import the build or re-compile the workspace, particularly if checking out different branches to get symbols to resolve properly again

# Using `sbt`

Most developer operations, such as builds and running tests, are performed via SBT.

## `sbt` settings
Make sure to configure the JVM heap size to at least 4G when using IntelliJ. In particular:
- In Intellij, under Settings, search for "sbt" and then under JRE add `-Xmx4G -Xms2G` to VM Parameters.
- In Intellij in the same menu, set the maximum heap size to at least 4000M.

## `sbt` Commands

This Section gives an overview of common sbt commands.
More commands can be found in build.sbt and BuildCommon.scala.
To run these commands, enter the `sbt` shell by running `sbt` in the repo root.

Alternatively, you can execute the commands outside the shell by running `sbt '<command>'`.
Note that the entire command must be quoted in this case, especially if it has spaces or special characters
(like the `testOnly` examples in the Test section below).

- `clean`: deletes all generated files (in the target directory)
- `clean-splice`: like clean but only for our own apps not for the Canton fork
- `compile`: compile production code (excluding test code)
- `Test/compile`: compile production and test code
- `apps-common/compile`: compile production code of the `apps-common` subproject
- `apps-frontends/compile`: compile and codegen only frontend code
- `apps-frontends/npmTest`: runs all frontend unit tests
- `apps-frontends/npmGenerateViteReport`: generates a html report of the vite frontend tests after the above was executed (named report.html located in the log folder)
- `apps-frontends/npmLint`: checks formatting of frontend code, but does not fix anything
- `apps-frontends/npmFix`: fixes formatting of frontend code
- `scalafixAll`: invoke scalafix across all configurations where scalafix is enabled.
    `scalafix` is a linting and rewrite tool. This may run for a long time as it needs to do a full compile.
- `format`: apply `scalafmt` to format source files
- `formatFix`: apply `scalafmt`, `sbt scalafixAll`, and `sbt apps-frontends/npmFix` to format source files
- `lint`: lint-check. Does not apply any fixes. Checks enforcement of `scalafmt`, `buf`, `scalafix`, `apps-frontends/npmLint`, and `shellcheck` rules
- `damlBuild`: create `.dar` files for all Daml projects
- `bundle`: create a release bundle in `apps/app/target/release/<version>`. The release binary is loaded into your PATH automatically via `direnv`. Simply run `amulet` to call it.
- `checkErrors`: check test log for errors and fail if there is one. Note that if you haven't deleted your local log file in a long time, this may find very old errors.
- `updateTestConfigForParallelRuns`: Updates the test configuration files that drive how tests are executed in parallel in CI. You need to run this when you add a new unit or integration test, and commit the changes to the test-*.log files that it saves, otherwise the static checks will fail in CI.
Test:
- `testOnly myWildcard`: runs all tests matching wildcard, e.g.,
  `testOnly com.digitalasset.myPackage.*` runs all tests in package `com.digitalasset.myPackage`.
  `testOnly *Wallet* -- -z "allow calling tap"` runs all tests with classname matching `*Wallet*` and test description matching `allow calling tap`.
- `test`: Note that it is [not currently advisable](https://github.com/DACH-NY/canton-network-node/issues/2098) to use this command. Use the `testOnly` commands described above to run specific tests and use the CI to run all tests.
- `damlTest`: run the Daml script tests included with the apps' Daml files

## `sbt` Tips&Tricks

Things sometimes go wrong with `sbt` in ways that are hard to debug. This section will list common tips&tricks for getting `sbt` to do what we want it to.
- In case you see unexpected build failures after switching branches, run `sbt reload` to have sbt update its internal state.
- In case you continue to see unexpected build failures, despite following every other trick in this section, you probably need to delete the sbt build files. If you suspect the build failures come from Splice build files, run `sbt clean-splice` to delete all Splice build files. If the error might come from the build files of the OS Canton dependency, run `sbt clean` to delete all build files managed by sbt. Subsequent `sbt Test/compile`s should then succeed.

## Unused Import Warnings

If the unused import, local variable or implicits warnings get in the way during development, you can locally turn them into
an info summary that just displays the number of warnings by creating the file `.disable-unused-warnings` and
calling `sbt reload`. Note that this requires a partial re-compile.

## App Configuration Files

The applications are configured using [HOCON](https://github.com/lightbend/config/blob/main/HOCON.md) files.

Run `./scripts/print-config.sh FILE` in order to retrieve a canonical form of a given
config file, with includes and substitutions resolved and comments stripped.

**NOTE:** Although in some cases, the use of system props may work, only environment variables are fully supported
across the stack. As a result, please ensure that you use environment variables for all overrides.

## Editing Daml

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

## Daml Version Guards in Integration Tests

Our backends need to handle the case where we compile against Daml
version X+1 but the vote to switch to those models is not yet done and
only Daml version X can be used. This is done by checking the active
version endpoints in triggers, UIs and similar. However, those checks
can be easily forgotten and the normal integration tests do not catch
that as they run against the latest version.

To catch this, we periodically run all integration tests against the
latest Daml version that was shipped to mainnet. This means tests that
do actually depend on the newer versions won't work. Default to making
tests backwards compatible where possible. Where this is not possible
or does not make sense (e.g. because you're testing functionality that
did not have an equivalent before), tag the test with a scalatest tag
corresponding to the version of the Daml package that introduced the
functionality, e.g.,
`org.lfdecentralizedtrust.splice.util.scalatesttags.SpliceAmulet_0_1_9`. This
ensures the test will be excluded if we are running against earlier
Daml versions. When we run the integration tests against older Daml
versions, we pass scalatest tag exclusions belonging to newer Daml
package versions as well as
`org.lfdecentralizedtrust.splice.util.scalatesttags.NoDamlCompatibilityCheck`.
This is accomplished by writing out a `scala_test_tags` file which
contains a list of additional arguments passed to scalatest which is
then picked up when running the tests.

To trigger the compat tests for a PR go to
https://github.com/hyperledger-labs/splice/actions/workflows/build.yml
and click on `Run workflow`. Select the branch of your PR and the
splice version you want to test compatibility against and input the
commit sha of your branch.

## Deprecating old Daml code and removing Support Code

To keep our code clean and remove unused logic which makes code harder
to reason about, increases maintenance effort and makes code audits
harder, we periodically increase the minimum Daml version that we
still support.

Our rule for doing so is that we wait for a Daml version to be
*effective on mainnet for one month* before bumping the minimum
supported version and cleaning up associated code. Note that this rule
assumes that the removal is not user facing, e.g., it won't break app
devs or validator operators. If you are unsure about that, discuss it
with the other splice maintainers.

To bump the minimum version, go to `DarResources.scala` and edit the
`minimumInitialization` field in the respective
`PackageResource`. After doing that you can trigger CI. All the
version checks from from `PackageVersionSupport` will warn for checks
that are now redundant because the version they are testing against is
now redundant so you can delete them and the code relying on them.

# Troubleshooting

## Nix Issues on MacOS
On a MacOS, If you previously installed nix and suddenly have no access to the command. As a result, when reloading the project environment, you may see something like `./.envrc:8: nix: command not found`. To fix this, first verify that the executable is available in `/nix/var/nix/profiles/default/bin/nix`. If yes, then you may want to add the folowing as seen [here](https://github.com/NixOS/nix/issues/3616#issuecomment-903869569) to your (bash|zsh)rc file.

```
  # Nix
    if [ -e '/nix/var/nix/profiles/default/etc/profile.d/nix-daemon.sh' ]; then
      source '/nix/var/nix/profiles/default/etc/profile.d/nix-daemon.sh'
    fi
  # End Nix
```

## NPM Lock file issues

CI enforces that the checked in lock files are up2date. However, for
the generated JS codegen, you might see issues where CI claims those
are not up2date.

The cause of this is a damlc issue where incremental builds do not
produce the same package id. This will be fixed in later versions.

To make sure your lock files match CI, run the following steps:

1. `find . -name '.daml' | xargs rm -r`
2. `sbt compile`
3. Check-in the updated lock file which should now match CI.

## Cpu.registerObservers exception

If you are encountering an exception:
```
Exception in thread "main" java.lang.ExceptionInInitializerError
        at io.opentelemetry.instrumentation.runtimemetrics.java8.Cpu.registerObservers(Cpu.java:51)
        at com.digitalasset.canton.metrics.MetricsConfig$JvmMetrics$.setup(MetricsRegistry.scala:97)
[...]
Caused by: java.lang.NullPointerException: Cannot invoke "jdk.internal.platform.CgroupInfo.getMountPoint()" because "anyController" is null
        at java.base/jdk.internal.platform.cgroupv2.CgroupV2Subsystem.getInstance(CgroupV2Subsystem.java:80)
```
in start-canton.sh, try adding: `export ADDITIONAL_JAVA_TOOLS_OPTIONS="-XX:-UseContainerSupport"` to .envrc.private
