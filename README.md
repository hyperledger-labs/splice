# canton-coin

## Contribution Guide

We share a lot of our tooling and processes with Canton.
We thus use [Canton's Contributing Guide](https://github.com/DACH-NY/canton/blob/main/contributing/README.md)
as a baseline, and list below only the points where we differ.

Please read the above guide in particular to learn about metrics, logging, tracing, Scala guidelines, Protobuf guidelines, TODO notes, formatting and git hooks.

## Setup

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

If you encounter issues, try exiting & reentering the directory to reactivate direnv.

### As a new joiner
For your first issue, you can take a look at [issues labelled with the `starter` tag](https://github.com/DACH-NY/the-real-canton-coin/issues?q=is%3Aissue+label%3Astarter). Else, please ask your onboarding
buddy for help with getting started on the code base.

## Directory layout

See the `README.md` files in the top-level directories for details.

Most top-level directories logically correspond to independent repositories,
which are currently maintained within this one repository for increased
delivery velocity using head-based development.

We expect to start splitting off repositories as part of the work to deliver
M3 - TestNet Launch.

## IntelliJ setup

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


## sbt
### sbt settings
Make sure to configure the JVM heap size to at least 4G when using IntelliJ. In particular:
- In Intellij, under Settings, search for "sbt" and then under JRE add `-Xmx4G -Xms2G` to VM Parameters.
- In Intellij in the same menu, set the maximum heap size to at least 4000M.

### sbt Commands

This Section gives an overview of common sbt commands.
More commands can be found in build.sbt and BuildCommon.scala.

- `clean`: deletes all generated files (in the target directory)
- `clean-cn`: like clean but only for our own apps not for the Canton fork
- `compile`: compile production code (excluding test code)
- `Test/compile`: compile production and test code
- `apps-common/compile`: compile production code of the `apps-common` subproject
- `scalafixAll`: invoke scalafix across all configurations where scalafix is enabled.
    `scalafix` is a linting and rewrite tool we use to organize imports. This may run for a long time as it needs to do a full compile.
- `format`: apply `scalafmt` to format source files
- `lint`: lint-check. Does not apply any fixes, but checks more things than `format`.
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

## Unused Import Warnings

If the unused import, local variable or implicits warnings get in the way during development, you can locally turn them into
an info summary that just displays the number of warnings by creating the file `.disable-unused-warnings` and
calling `sbt reload`. Note that this requires a partial re-compile.


## TODO Comments

Call `./scripts/check-todos.sh` to extract a list of all TODO comments in the project,
and get a list of TODO comments that do not conform [Canton's TODO style guidelines](https://github.com/DACH-NY/canton/blob/main/contributing/README.md#todo-comments).
Note that in contrast to these guidelines, we
- disallow `FIXME` comments on `main`
- use a double-number milestone format, e.g. `M1-92`
- ignore `.. todo::` comments in .rst files
- use normal .rst comments, e.g., `.. TODO(M1-14): some left-over doc work`

Note that these guidelines are enforced in CI.

### Managing Canton for Tests

To speed up our tests our tests run against a long-running Canton instance.
To start the instance run `./start-canton.sh`. It can be stopped via `./stop-canton.sh`.

You should only need to restart it if you change
`apps/app/src/test/resources/simple-topology-canton.conf`. If you
encounter an error like the following, there might have been a problem
with the running Canton instance so try restarting.

```
ERROR c.d.n.e.CoinLedgerConnection$$anon$1:WalletIntegrationTest/SVC=svc-app - Failed to instantiate ledger client due to connection failure, exiting...
```

### Running and debugging integration tests

The integration tests are located at [`/apps/app/src/test/scala/com/daml/network/integration/tests/`](/apps/app/src/test/scala/com/daml/network/integration/tests).
They work by standing up by defining and starting a Canton network topology and running Canton console commands against that topology,
see for example the [`DirectoryIntegrationTest.scala`](/apps/app/src/test/scala/com/daml/network/integration/tests/DirectoryIntegrationTest.scala).
Also see the Scaladocs on Canton's [`BaseIntegrationTest.scala`](/canton/community/app/src/test/scala/com/digitalasset/canton/integration/BaseIntegrationTest.scala) for more information about the intended usage of the test framework.

Many tests use the topology and base configuration defined in [`/apps/app/src/test/resources/simple-topology.conf`](apps/app/src/test/resources/simple-topology.conf), or a variant thereof.
Adjusting these configurations can sometimes help with debugging.
See for example https://docs.daml.com/canton/usermanual/monitoring.html on how to adjust logging and monitoring for Canton nodes.

You can run integration tests from IntelliJ by navigating to the file and clicking the little green "run-triangle"
in the gutter at the start of the test definition.
You can also run them from `sbt` as explained in the section on `sbt` below.
The logs from test executions are output to `/logs/canton_test.log`.
Use `lnav` to view these logs for debugging failing test cases.
No installation of `lnav` is required, as it is provided by default by our `direnv`.

### Running the preflight check

The preflight check runs an integration test where a local validator connects to a global canton network.

To run the preflight check against devnet (see section `GCE Clusters`), use
```
sbt "testOnly *PreflightIntegrationTest"
```

To run the preflight check against ScratchNet, use
```
sbt -DDOMAIN_URL=http://scratch.network.canton.global:5008 -DNETWORK_APPS_ADDRESS=scratch.network.canton.global "testOnly *PreflightIntegrationTest"
```

To run the preflight check against Staging, use
```
sbt -DDOMAIN_URL=http://staging.network.canton.global:5008 -DNETWORK_APPS_ADDRESS=staging.network.canton.global "testOnly *PreflightIntegrationTest"
```

Note that the preflight check will fail if you branch is sufficiently divergent from the main branch
(in particular, if you made any changes to the Daml model).

#### Setting up `lnav` to inspect Canton logs

If you have never used `lnav` to inspect Canton logs, then we recommend:
1. Download the `canton.lnav.json` log format config file from https://github.com/DACH-NY/canton/blob/main/canton.lnav.json
2. Install the Canton log format using `lnav -i canton.lnav.json`, which will install it in `~/.lnav/formats/installed/canton_log.json` and enable it for auto-detection in future `lnav` sessions.
3. Type `lnav log/canton_test.log` to inspect the test logs.
4. Take the time to familiarize yourself with docs for the `lnav` [UI](https://docs.lnav.org/en/latest/ui.html#ui)
   and [HotKeys](https://docs.lnav.org/en/latest/hotkeys.html), and learn to effectively navigate the test logs.
   The Canton docs also contain a [short tutorial](https://docs.daml.com/canton/usermanual/monitoring.html#viewing-logs) highlighting the most relevant features and hotkeys.


#### Handling errors in integration tests

Generally, errors in integration tests should be handled through using Canton's `com.digitalasset.canton.logging.SuppressingLogger`.
The suppressing logger allows you to, e.g., specify a warning you expect to see and then ensures that it is isn't emitted
as a warning to the log.
If it would be emitted as a warning to a log, CI would fail as we ensure via `check-logs.sh` (or analogue: `sbt checkErrors`)
and `check-sbt-output.sh` that no unexpected warnings or errors that our integration tests log no unexpected warnings
or errors.

The easiest way to how to use `SuppressingLogger` is by looking at existing usages of its methods.
If you don't find an usage of a given method within the CN network repo, you can look for usages in the Canton repo.

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

## Building the Wallet & Splitwise frontend

To build the wallet frontend you first need to generate the TypeScript
files based on our protobuf files as well as run the Daml codegen on
our daml models.

In order to pass in CI, source code must be formatted by `prettier`.

1. Generating protobuf files:

```
sbt protocGenerate damlBuild # Generate typescript for our own protobufs
```

2. Build the wallet frontend:
```
cd apps/wallet/frontend
./gen-ledger-api-proto.sh # generate typescript for ledger API protobufs
./copy-proto-sources.sh # Copy the generated files to the right place
./codegen.sh # Run Daml Codegen
npm install
npm run build
```

2. Build the splitwise frontend:

```
cd apps/splitwise/frontend
./gen-ledger-api-proto.sh # generate typescript for ledger API protobufs
./copy-proto-sources.sh # Copy the generated files to the right place
./codegen.sh # Run Daml Codegen
npm install
npm run build
```

3. Code formatting:

```
npm run format:fix
```


## Running the wallet and splitwise frontend

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

4. Start the envoy grpc-web proxy for the wallet apps

```
cd apps/wallet/frontend
./start-envoy.sh
```

This starts a grpc-web proxy on port 8080 for Alice’s wallet and on
port 8081 for Bob’s wallet.

5. Start the envoy grpc-web proxy for the splitwise apps

```
cd apps/splitwise/frontend
./start-envoy.sh
```

This starts a grpc-web proxy on port 8082 for Alice’s wallet and on
port 8083 for Bob’s wallet.

5. Start the frontends:

```
cd apps/wallet/frontend
PORT=3000 REACT_APP_GRPC_URL=http://localhost:8080 npm start # Alice's wallet
PORT=3001 REACT_APP_GRPC_URL=http://localhost:8081 npm start # Bob's wallet
cd apps/splitwise/frontend
PORT=3002 REACT _APP_LEDGER_API_GRPC_URL=http://localhost:8085 npm start # Alice’s splitwise
PORT=3003 REACT _APP_LEDGER_API_GRPC_URL=http://localhost:8086 npm start # Bob’s splitwise
```

You can login as `alice_wallet_user` and `bob_wallet_user` respectively.

If the frontend shows up but nothing happens when you click tap or
other buttons, check your browser console. If you see a stream of 503 errors,
iptables on your host might be blocking the connections. Try running on the host:
```
sudo iptables -A INPUT -i docker0 -p tcp -m tcp --dport 5000:5999 -j ACCEPT
```
To make this change persistent, type:
```
sudo sh -c 'iptables-save > /etc/iptables/rules.v4'
```

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

## Protobuf & GRPC Guidelines

Generally endeavor to model your [GRPC and Protobuf definitions using Google's guidelines](https://cloud.google.com/apis/design/naming_convention).
We use [`buf`](https://docs.buf.build/tour/lint-your-api)'s `DEFAULT` linting rules to enforce many of these guidelines in CI using the `protobufLint` `sbt` task.
See [their documentation](https://docs.buf.build/lint/rules#default) for what these are and why they are beneficial.

Below we list additional rules specific to our project:

### Message Definitions

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


## GCE Clusters

The public Canton Network clusters are currently hosted in Google
Cloud. There are multiple clusters, all of which are accessible only
through VPN.

* DevNet - http://dev.network.canton.global
* ScratchNet - http://scratch.network.canton.global
* Staging - http://staging.network.canton.global

The DevNet cluster is automatically updated every day at
[0650 UTC](/.circleci/run-schedule-pipeline.json) via
[CI/CD](/.circleci/config.yml). It is intended to be a more stable and
production like environment. There are Slack notifications issued when
these updates complete, either successfully or with a failure.

The ScratchNet cluster is manually managed and intended to be a test
bed for new code and deployment process updates. Additional clusters
can be created without difficulty, although with additional running
costs.

### Connecting to a Cluster

The GCE clusters expose both the Canton Domain and Ledger APIs to the
outside world via four [Digital Asset VPNs](https://digitalasset.atlassian.net/wiki/spaces/DEVSECOPS/pages/1076822828/VPN+IP+Whitelist+for+Digital+Asset):

* GCP Virginia Full Tunnel
* GCP Frankfurt Full Tunnel
* GCP Sydney Full Tunnel
* GCP DA Canton DevNet

The Full Tunnel VPNs are available to all internal DA users and allow
authenticated access to both the Canton Network services and cluster
adminstration APIs. (Only the Canton Network development team has
administrative authorization to the clusters.)

The "GCP DA Canton DevNet" VPN is the VPN used for customer
access to the cluster. User accounts can be added through a
[manual e-mail request to IT](mailto:help@digitalasset.com).
Connections through this VPN only have access to public cluster
services and not the administration APIs.

Provided you are connecting through one of the listed VPNs, the following
APIs are available in each environment:

| Service            | API        | Port |
| ------------------ | ---------- | ---- |
| Canton Domain      | Public API | 5008 |
| Canton Domain      | [Admin API](https://docs.daml.com/canton/usermanual/administration.html#domain-admin-apis)  | 5009 |
| Canton Participant | [Ledger API](https://docs.daml.com/app-dev/grpc/proto-docs.html) | 5001 |
| Canton Participant | [Admin API](https://docs.daml.com/canton/usermanual/administration.html#canton-administration-apis)  | 5002 |

(The port allocation scheme is documented [here](./apps/app/src/test/resources/README.md)
and is shared between the Canton Network tests and runtime environment.)

It is possible to connect locally hosted Canton components into this
environment. This includes the REPL, participant nodes, and domain nodes.
If you don't have Canton, you may install it following
instructions [here](https://docs.daml.com/canton/usermanual/installation.html).

This is an example of a configuration file that might be used with a
Canton Network cluster. It connects a local REPL to a (`DevNet`)
cluster participant and creates a locally hosted participant.

```
canton {
  remote-participants {
    remoteParticipant1 {
      admin-api {
        port = 5002
        address = dev.network.canton.global
      }
      ledger-api {
        port = 5001
        address = dev.network.canton.global
      }
    }
  }
  participants {
    localParticipant1 {
      storage.type = memory
      admin-api.port = 5002
      ledger-api.port = 5001
    }
 }
}
```

This can be used to start a REPL as follows:

```
$ bin/canton -c cn.conf
```

Once the REPL is running, it is then possible to interact with the
cluster via the REPL.

This is an example of a locally requested ping from the remote
participant to the remote participant. This command confirms the ledger API
connection to the remote participant node:

```
@ remoteParticipant1.health.ping(remoteParticipant1, timeout = 10.seconds)
res0: concurrent.duration.Duration = 2246 milliseconds
```

The local participant node may connected to the CN domain as follows:


```
@ localParticipant1.domains.connect("test", "http://dev.network.canton.global:5008")
res1: DomainConnectionConfig = DomainConnectionConfig(
  domain = Domain 'test',
  sequencerConnection = GrpcSequencerConnection(
    endpoints = http://34.173.2.69:5008,
    transportSecurity = false,
    customTrustCertificates = None()
  ),
  manualConnect = false,
  domainId = None(),
  priority = 0,
  initialRetryDelay = None(),
  maxRetryDelay = None()
)
```

Once that connection is established, it is possible to conduct ledger
interactions that span both the CN cluster participant and the locally
hosted participant:

```
@ remoteParticipant1.health.ping(localParticipant1, timeout = 10.seconds)
res2: concurrent.duration.Duration = 1762 milliseconds
```

As part of the runbook a participant node is also spun up
and connects to the DevNet domain. Therefore, the runbook contains
alternative scripts for connecting a local participant to the DevNet domain.

### Cluster Tooling

This repository also contains tools for managing clusters hosted in
These clusters run in the Google Cloud, using Google's GKE
implementation of Kubernetes.  The specific configuration for these clusters
is defined in a mangest generated by
[`canton-network-config.jsonnet`](/cluster/canton-network-config.jsonnet).

All cluster management commands are defined as subcommands of the
`cncluster` script.

#### Docker image hosting

Docker images for both local and GCE clusters are stored in the Google
Cloud [Artifact Registry](https://cloud.google.com/artifact-registry).
The specific registry is identified with the following environment
variables, which are defined in [`.envrc.vars`](.envrc.vars):

| Variable Name             | Meaning                                                               |
| ------------------        | --------------------------------------------------------------------- |
| `CLOUDSDK_COMPUTE_REGION` | Google Cloud Region in which resources will be created                |
| `CLOUDSDK_CORE_PROJECT`   | ID of the Google Cloud project in which the cluster is located.       |
| `GCP_REPO_NAME`           | Google Cloud Project/Name of the image repository used to manage project container images. |

The `.envrc` mechanism is also used to ensure that the current user is
authenticated properly against GCE.

#### Cluster Management Operations

Operations against GCE clusters are complicated by the facts that there
are more than one cluster and these clusters are usually shared.  To
accommodate this, there is a [`deployment`](/deployment) directory
that is used to manage the configuration of each cluster. Each extant
cluster has a directory under `deployments` and operations against
that cluster must be invoked from within that directory. This reduces
the possibility of operating on the wrong cluster, and allows the use
of the `.envrc` mechanism to provide whatever configuration is
necessary to identify a given cluster.

Available operations include:


* `cncluster apply` - Apply the cluster configuration to the currently
  running CN cluster in GCE.
* `cncluster check` - Run a series of simple validity checks against the
  external API exposed by a cluster.
* `cncluster create` - Create a new instance of the CN cluster in GCE,
  if it does not already exist.
* `cncluster delete` - Delete the currently running CN cluster from GCE.
* `cncluster ipaddr` - Return the toplevel IP address of the cluster.
* `cncluster push` - Update the deployment tag for a single running
  module in the cluster. (This allows individual modules to be tested
  in the context of an existing cluster.)

Internally, these operations rely on the following environment
variables. As stated above, these are usually populated via `.envrc`.

| Variable Name             | Meaning                                                               |
| ------------------        | --------------------------------------------------------------------- |
| `CLOUDSDK_COMPUTE_REGION` | Google Cloud Region in which resources will be created                |
| `CLOUDSDK_CORE_PROJECT`   | ID of the Google Cloud project in which the cluster is located.       |
| `GCP_CLUSTER_BASENAME`        | Base of the cluster within the cloud project.  Used to compute the cluster's full name and DNS name.                   |
| `GCP_REPO_NAME`           | Google Cloud Project/Name of the image repository used to manage project container images. |

#### Recovery from a Failed CI/CD Deployment

If the nightly CI/CD deployment results in an inoperative cluster, the
CI/CD deployment can be manually run from a development laptop during
the day. **For this to work, the working directory for the git
repository must be in a clean state without uncommitted changes.**

First, from the root of the working directory, rebuild the required
docker images from scratch:

`make clean docker-build`

Secondly, from the same directory, push the docker images to the
artifact registry with the appropriate tags.

`CI=true make docker-push`

Setting `CI` to `true` requests a CI build. This enforces cleanliness
of the working copy and generates image tags that do not contain a
username prefix.

Finally, apply the changes to the cluster. This is an example of
applying cluster changes to DevNet.  (For this to work, you will
need to be connected to the VPN.)

`(cd deployment/devnet && CI=true cncluster apply)`

Successful pod deplomyment can then be checked:

`(cd deployment/devnet && kubectl get pods)`

This should produce a list of pods, all in running status:

```
$ kubectl get pods
NAME                                  READY   STATUS    RESTARTS   AGE
canton-domain-5d567849cf-zjnmq        0/1     Running   0          12s
canton-participant-6668587bb8-cs757   1/1     Running   0          12s
docs-5544ffb45b-wmgpv                 1/1     Running   0          12s
svc-app-654d7ddc9c-xqjfb              1/1     Running   0          11s
```

If Kubernetes is unable to pull the image for a pod, the status might
look something like this:

```
$ kubectl get pods
NAME                                  READY   STATUS             RESTARTS      AGE
canton-domain-5485476484-7ls9w        1/1     Running            0             108s
canton-domain-6bfc8d8585-2wj55        0/1     ImagePullBackOff   0             15s
canton-participant-6685859869-nh9vm   1/1     Running            1 (45s ago)   108s
canton-participant-7c9674cfd6-8fj7v   0/1     ImagePullBackOff   0             14s
docs-55f7b8967-67vcw                  1/1     Running            0             108s
docs-78dddd9c8b-5swzv                 0/1     ImagePullBackOff   0             17s
svc-app-6654f84564-bnvwq              1/1     Running            2 (64s ago)   108s
svc-app-84f954fb99-6ccw5              0/1     ImagePullBackOff   0             16s
```

The `ImagePullBackOff` status indicates that Kubernetes is waiting for
a timeout to elapse before attemping to pull the image again. There
can still be pods in `Running` status, due to the fact that we use
Kubernetes deployment objects that wait for an updated pod to be
running before stopping the previous pod. You can look for this
scenario by checking the ages of the running vs. failed pods.

To skip the image pull backoff timeout, you can delete the failed pod,
which will force an immediate recreate of the pod and attempt to
repull the image.

```kubectl delete pod ${POD_NAME}```

**Every** pod can be deleted and reset as follows. This can be useful
for the moment, given that we rebuild the cluster nightly anyway.

```
cncluster reset
```

### Ledger API Port Allocations

Both our deployment and tests follow the [port allocation scheme](./apps/app/src/test/resources/README.md).

### Bumping our Canton fork

Current Canton commit: `606a286a97e3bc47d19f3045048dd71b12a9800a`

1. Check out the [Canton Open Source repo](https://github.com/digital-asset/canton) at the current Canton commit listed above.
   NOTE: if you can't find the the comment, then you are probably using the closed source https://github.com/DACH-NY/canton repo.
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
    2. `releaseVersionToProtocolVersion` in `ReleaseVersionToProtocolVersions.scala`
    3. `version` in `CantonDependencies`
    4. In `nix/canton.nix`:
       1. Bump `version` to the desired canton version
       2. Bump `sdk_version` to the associated sdk snapshot version
       3. Adjust the `sha256` digest by copying back the new hash when Nix throws an error during validation.
14. Bump the sdk version in our own daml.yaml files via `./set-sdk.sh $sdkversion` to the same version.
15. Create another commit, `git add -A && git commit -m"Bump Canton commit and Canton/SDK versions"`
16. Make a PR with your changes, so CI starts churning.
17. Test whether things compile using `sbt test:compile`.
    In case of problems, find the related change in the **closed source Canton repo** and use the change and its commit message to adjust our code.

You can refer to https://github.com/DACH-NY/the-real-canton-coin/pull/446/commits for an example of how the update PR should look like.
