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

- `compile`: compile production code (excluding test code)
- `test:compile`: compile production and test code
- `apps-common/compile`: compile production code of the `apps-common` subproject
- `scalafixAll`: invoke scalafix across all configurations where scalafix is enabled. 
    It's a linting and rewrite tool we use to organize imports. This may run for a long time as it needs to do a full compile.
- `format`: apply `scalafmt` to format source files
- `bundle`: create a release bundle in `apps/app/target/release/<version>`. Add the path to the release binary
    (`<path-to-repo>/the-real-canton-coin/apps/app/target/release/coin/bin`) to your PATH to be able to call it with `coin`. 

Test:
- `testOnly myWildcard`: runs all tests matching wildcard, e.g.,
  `testOnly com.digitalasset.myPackage.*` runs all tests in package `com.digitalasset.myPackage`.
- `test`: runs all tests

For more information, especially on metrics, logging, tracing, Scala guidelines, Protobuf guidelines, formatting and git hooks
please refer to the respective sections in [Canton's README](https://github.com/DACH-NY/canton/blob/main/contributing/README.md).
We share a lot of tooling with Canton, so to avoid duplication we use the documentation in the Canton repo 
as "one source of truth". 

## Cluster Tooling

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

### Docker image hosting

Docker images for both local and GCE clusters are stored in the Google
Cloud [Artifact Registry](https://cloud.google.com/artifact-registry).
The specific registry is identified with the following environment
variables, which are defined in [`.envrc.vars`](.envrc.vars):

| Variable Name             | Meaning                                                               |
| ------------------        | --------------------------------------------------------------------- |
| `CLOUDSDK_COMPUTE_REGION` | Google Cloud Region in which resources will be created                |
| `CLOUDSDK_CORE_PROJECT`   | ID of the Google Cloud project in which the cluster is located.       |
| `GCP_REPO_NAME`           | Name of the image repository used to manage project container images. |

The `.envrc` mechanism is also used to ensure that the current user is
authenticated properly aaginst GCE.

### Local Cluster Operations

The local cluster is managed with the following three subcommands of
`cnlocal`:

* `cnlocal start` - Start a local cluster if it has not already been
  started.
* `cnlocal apply` - Apply the current configuration to the currently
  running local cluster.
* `cnlocal stop` - Stop the local cluster, if it is running.

### GCE Operations

Operations against GCE clusters are complicated by the facts that
there are more than one cluster and GCE clusters are usually shared
resources. To accomodate this, there is a [`deployments`](deployments)
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
| `GCP_CLUSTER_NAME`        | Name of the GKE cluster within the cloud project.                     |
| `GCP_REPO_NAME`           | Name of the image repository used to manage project container images. |
