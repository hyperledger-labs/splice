# canton-coin

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

### Running and debugging integration tests

The integration tests are located at [`/apps/app/src/test/scala/com/daml/network/integration/tests/`](/apps/app/src/test/scala/com/daml/network/integration/tests).
They work by standing up by defining and starting a Canton network topology and running Canton console commands against that topology,
see for example the [`DirectoryProviderIntegrationTest.scala`](/apps/app/src/test/scala/com/daml/network/integration/tests/DirectoryProviderIntegrationTest.scala).
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

#### Setting up `lnav` to inspect Canton logs

If you have never used `lnav` to inspect Canton logs, then we recommend:
1. Download the `canton.lnav.json` log format config file from https://github.com/DACH-NY/canton/blob/main/canton.lnav.json
2. Install the Canton log format using `lnav -i canton.lnav.json`, which will install it in `~/.lnav/formats/installed/canton_log.json` and enable it for auto-detection in future `lnav` sessions.
3. Type `lnav log/canton_test.log` to inspect the test logs.
4. Take the time to familiarize yourself with docs for the `lnav` [UI](https://docs.lnav.org/en/latest/ui.html#ui)
   and [HotKeys](https://docs.lnav.org/en/latest/hotkeys.html), and learn to effectively navigate the test logs.


## sbt
### sbt settings
Make sure to configure the JVM heap size to at least 4G when using IntelliJ. In particular:
- In Intellij, under Settings, search for "sbt" and then under JRE add `-Xmx4G -Xms2G` to VM Parameters.
- In Intellij in the same menu, set the maximum heap size to at least 4000M.

### sbt Commands

This Section gives an overview of common sbt commands.
More commands can be found in build.sbt and BuildCommon.scala.

- `clean`: deletes all generated files (in the target directory)
- `compile`: compile production code (excluding test code)
- `Test/compile`: compile production and test code
- `apps-common/compile`: compile production code of the `apps-common` subproject
- `scalafixAll`: invoke scalafix across all configurations where scalafix is enabled.
    `scalafix` is a linting and rewrite tool we use to organize imports. This may run for a long time as it needs to do a full compile.
- `format`: apply `scalafmt` to format source files
- `damlBuild`: create `.dar` files for all Daml projects
- `protobufLint`: to lint our protobuf files using `buf`
- `bundle`: create a release bundle in `apps/app/target/release/<version>`. The release binary is loaded into your PATH automatically via `direnv`. Simply run `coin` to call it.

Test:
- `testOnly myWildcard`: runs all tests matching wildcard, e.g.,
  `testOnly com.digitalasset.myPackage.*` runs all tests in package `com.digitalasset.myPackage`.
  `testOnly *Wallet* -- -z "allow calling tap"` runs all tests with classname matching `*Wallet*` and test description matching `allow calling tap`.
- `test`: runs all tests (with the exception of some tests running against the cluster that are excluded on purpose - see the overwrite of `test` in `BuildCommon.scala` for more details)
- `damlTest`: run the Daml script tests included with the apps' Daml files

For more information, especially on metrics, logging, tracing, Scala guidelines, Protobuf guidelines, formatting and git hooks
please refer to the respective sections in [Canton's README](https://github.com/DACH-NY/canton/blob/main/contributing/README.md).
We share a lot of tooling with Canton, so to avoid duplication we use the documentation in the Canton repo
as "one source of truth".

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

### Daml Numerics

To represent Daml `Numeric`s for any user facing APIs (console commands), we use `scala.math.BigDecimal`s. 
We use Scala BigDecimals instead of Java BigDecimals (that are used in the Daml repo) because 
integers, floats etc. are automatically converted 
to Scala BigDecimals by the Scala compiler unlike Java BigDecimals 
(`wallet.tap(10)` vs `wallet.tap(new java.math.BigDecimal(10))`). 

To represent Daml Numerics in Protobuf we use `string`s (there is no Protobuf BigDecimal type). Conversions to 
and from `string`s should occur via `Numeric.toString` and `Numeric.fromString` from `com.daml.lf.data.Numeric`.

When interacting with the Ledger API, we convert the Scala BigDecimals to Java BigDecimals. 

Overall, please refer to the `wallet.tap` command implementation for the canonical handling of Daml Numerics.  

## GCE Clusters

The public Canton Network clusters are currently hosted in Google
Cloud. There are two clusters, both of which are accessible only
through VPN.

* DevNet - http://dev.network.canton.global
* ScratchNet - http://scratch.network.canton.global

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

This repository also contains tools for managing clusters, hosted both
locally and in GCE. Local clusters are run on your local development
machine, using minikube. GCE clusters are run in the Google Cloud,
using Google's GKE implementation of Kubernetes. In both cases, a common
Kubernetes cluster manifest is used, defined in
[`canton-network-config.jsonnet`](/cluster/canton-network-config.jsonnet).

To avoid the risk of confusing local and GCE cluster operations, two
separate top level commands are used for each. `cnlocal` is the
entrypoint for local cluster operations and `cncluster` is the
entrypoint for GCE cluster operations.

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

#### Local Cluster Operations

The local cluster is managed with the following three subcommands of
`cnlocal`:

* `cnlocal start` - Start a local cluster if it has not already been
  started.
* `cnlocal apply` - Apply the current configuration to the currently
  running local cluster.
* `cnlocal stop` - Stop the local cluster, if it is running.

#### Cluster Management Operations

Operations against GCE clusters are complicated by the facts that
there are more than one cluster and GCE clusters are usually shared
resources. To accommodate this, there is a [`deployment`](/deployment)
directory that is used to manage the configuration of each
cluster. Each extant cluster has a directory under `deployments` and
operations against that cluster must be invoked from within that
directory. This reduces the possibility of operating on the wrong
cluster, and allows the use of the `.envrc` mechanism to provide
whatever configuration is necessary to identify a given cluster.

Available operations include:

* `cncluster create` - Create a new instance of the CN cluster in GCE,
  if it does not already exist.
* `cncluster ipaddr` - Return the toplevel IP address of the cluster.
* `cncluster apply` - Apply the cluster configuration to the currently
  running CN cluster in GCE.
* `cncluster delete` - Delete the currently running CN cluster from GCE.

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
kubectl get pods | grep -v NAME | gawk '{print $1}' | xargs kubectl delete pod
```

### Ledger API Port Allocations

Both our deployment and tests follow the [port allocation scheme](./apps/app/src/test/resources/README.md).

### Bumping our Canton fork

1. Check out the [Canton Open Source repo](https://github.com/digital-asset/canton) at the Canton commit listed below.
2. Create a Canton patch file capturing all our changes relative to that `./scripts/diff-canton.sh path/to/canton-oss/ > canton.patch`
3. Undo our changes: `git apply '--exclude=canton/community/app/src/test/resources/examples/*' --directory=canton -R canton.patch`
   The exclusion is because those files are under a symlink and we don’t want to change them twice.
4. Create a commit to ease review.
5. Now check out the commit in the Canton OSS repo that you want to update to and update the current commit below.
6. Copy the Canton changes: `./scripts-copy-canton.sh path/to/canton-oss`
7. Create a commit to ease review.
8. Reapply our changes `git apply '--exclude=canton/community/app/src/test/resources/examples/*' --directory=canton --reject canton.patch`
   and resolve any conflicts (if any).
9. Bump the SDK version in `releaseVersionToProtocolVersion` in `CantonVersion.scala` and bump the version in `CantonDependencies` to match the SDK version declared in
   https://github.com/digital-asset/canton/blob/main/project/project/DamlVersions.scala for your respective commit.
10. Bump the sdk version in our own daml.yaml files via `./set-sdk.sh $sdkversion` to the same version.
11. Create another commit.
12. Make a PR with your changes.
You can refer to https://github.com/DACH-NY/the-real-canton-coin/pull/446/commits for an example of how the update PR should look like. 

Current Canton commit: 9e5ec309ad5fe229e53ab668b6b180db67b555bc

