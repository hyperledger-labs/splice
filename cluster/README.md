# Table of Contents

1. [Available Clusters](#available-clusters)
1. [Connecting to a Cluster](#connecting-to-a-cluster)
   1. [Granting VPN Access to External Partners](#granting-vpn-access-to-external-partners)
   1. [Available Cluster Services](#available-cluster-services)
   1. [Connecting Locally Hosted Canton Network Apps to a Cluster](#connecting-locally-hosted-canton-network-apps-to-a-cluster)
   1. [Connecting Locally Hosted Canton Components to a Cluster](#connecting-locally-hosted-canton-components-to-a-cluster)
   1. [Network Configuration Within Kubernetes](#network-configuration-within-kubernetes)
1. [Cluster Tooling](#cluster-tooling)
   1. [Manual Google Cloud Configuration](#manual-google-cloud-configuration)
   1. [Docker Image Hosting](#docker-image-hosting)
   1. [Cluster Management Operations](#cluster-management-operations)
   1. [Manually Deploying via CI](#manually-deploying-via-ci)
   1. [Observing Cluster Operation](#observing-cluster-operation)
      1. [Kubectl and `cncluster` operations.](#kubectl-and-cncluster-operations.)
      1. [GCE Dashboards](#gce-dashboards)
      1. [GCE Log Explorer](#gce-log-explorer)
         1. [Exclude noisy/non-JSON containers](#exclude-noisy-non-json-containers)
         1. [Manual configuration actions taken by DA employees](#manual-configuration-actions-taken-by-da-employees)
         1. [Configuration actions initiated by CircleCI](#configuration-actions-initiated-by-circleci)
         1. [Pod error states](#pod-error-states)
         1. [Check for Cluster Updates](#check-for-cluster-updates)
         1. [Check for Autoscaler Activity](#check-for-autoscaler-activity)
      1. [Canton Ledger Prometheus Metrics](#canton-ledger-prometheus-metrics)
   1. [Checking Pod Node Assignments and Memory Usage](#checking-pod-node-assignments-and-memory-usage)
1. [Interacting with a Canton Network Cluster](#interacting-with-a-canton-network-cluster)
   1. [Gaining Access to a Cluster](#gaining-access-to-a-cluster)
      1. [Fixing Connection Issues in kubectl](#fixing-connection-issues-in-kubectl)
   1. [Deploy a Build to a Cluster](#deploy-a-build-to-a-cluster)
   1. [Update a Single Component in a Cluster](#update-a-single-component-in-a-cluster)
   1. [Add a Component to the Build](#add-a-component-to-the-build)
   1. [Memory Settings](#memory-settings)
1. [TLS Certificate Provisioning](#tls-certificate-provisioning)
   1. [First-time Infra Setup](#first-time-infra-setup)
   1. [Cluster Configuration](#cluster-configuration)
   1. [Adding TLS to `{insert-service-here}`](#adding-tls-to-insert-service-here)
   1. [Force-updating the certificate](#force-updating-the-certificate)
1. [Auth0 secrets](#auth0-secrets)
1. [Participant User Configuration](#participant-user-configuration)
1. [Token configuration](#token-configuration)
1. [Pulumi and Helm](#pulumi-and-helm)
1. [Appendix: Kubernetes Resources](#appendix-kubernetes-resources)
   1. [Manifests](#manifests)

Note that operations in this directory require authentication to use
Google Cloud APIs. If you have `direnv` installed (which you should),
you will be asked to authenticate when you change into this directory
for the first ##qtime.

## Available Clusters

The public Canton Network clusters are currently hosted in Google
Cloud. There are multiple clusters, each with a different purpose, all
of which are accessible only through VPN:

| Cluster        | URL                                      | Deployment Policy                | Purpose                            |
|----------------|------------------------------------------|----------------------------------|------------------------------------|
| TestNet        | http://test.network.canton.global        | Weekly, Midnight UTC Sunday      | Longer Running Tests               |
| DevNet         | http://dev.network.canton.global         | Nightly, 6AM UTC                 | Current, Tested `main`             |
| Staging        | http://staging.network.canton.global     | After every push to `main`       | Latest `main`                      |
| ScratchNet     | http://scratch.network.canton.global     | Ad hoc, manual                   | Cluster Configuration Development  |
| ScratchNetB    | http://scratchb.network.canton.global    | Ad hoc, manual                   | Cluster Configuration Development  |
| ScratchNetC    | http://scratchc.network.canton.global    | Ad hoc, manual                   | Cluster Configuration Development  |
| SqlScratchNet  | http://sqlscratch.network.canton.global  | Ad hoc, manual                   | CloudSQL Configuration Prototype   |

The automatic deployments are configured as
[Scheduled](https://app.circleci.com/settings/project/github/DACH-NY/the-real-canton-coin/triggers?return-to=https%3A%2F%2Fapp.circleci.com%2Fpipelines%2Fgithub%2FDACH-NY%2Fthe-real-canton-coin)
[CI/CD](/.circleci/config.yml) in CircleCI.

The ScratchNet, ScratchNetB, ScratchNetC, and SqlScratchNet clusters
are manually managed and intended to be test beds for new code,
deployment process updates, and CloudSQL integration. These are a
shared resource, so please coordinate with the team prior to making
changes.

## Connecting to a Cluster

The GCE clusters currently expose all of their services to the
outside world exclusively via four [Digital Asset VPNs](https://digitalasset.atlassian.net/wiki/spaces/DEVSECOPS/pages/1076822828/VPN+IP+Whitelist+for+Digital+Asset):

* Internal Digital Asset (Cluster Services and Kubernetes Management APIs)
   * GCP Virginia Full Tunnel
   * GCP Frankfurt Full Tunnel
   * GCP Sydney Full Tunnel
* For External Users and Circle CI Preflight Testing (Cluster Services Only)
   * GCP DA Canton DevNet
* For consultants working on CN
   * AWS DA Consultant VPN

Even though the Kubernetes management API is accessible to all users
connecting through one of the internal VPNs, there are Google IAM
restrictions on those APIs that grant access only to appropriate
users. CircleCI has its access to the Kubernetes Management APIs (for
continuous deployment) through a specific grant to those servers' IP
addresses and a service account.

### Granting VPN Access to External Partners

For an external partner to have access to a Canton Network cluster,
they must be granted access to the *GCP DA Canton DevNet* VPN. The
request to grant this access must go through an approval and
notification process, with the setup itself done by IT
support. Partner access through this VPN is only to public cluster
services and not the administration APIs.

The process by which access is granted is this:

* Digital Asset sales/etc. personnel identify an individual at an
  external partner that needs access to a Canton Network
  cluster. (Currently, access can not be restricted to a single
  cluster.)
* They then enter a support ticket, with "Canton Network" as product
  code. The request will be to create an external login for the Canton
  Network customer VPN. (Specifically, This is the VPN created to
  resolve this [support ticket](https://help.digitalasset.com/s/case/5004x00000GMkxTAAT).)
* Wayne Collier must approve the addition of the new account, and
  Itai Segall must be notified.  Once the approval is complete, a
  request goes to Edward Newman's team via a
  [manual e-mail request to IT](mailto:help@digitalasset.com) to add the
  account and send documentation to the external user.
* Once the account has been confirmed, the ticket can be closed.

### Available Cluster Services

Provided you are connecting through one of the listed VPNs, a full
list of services provided through the cluster is available via
`cncluster ports`.

### Connecting Locally Hosted Canton Network Apps to a Cluster

The preferred way to connect a locally hosted Canton Network App to a
cluster is documented in the externally facing documentation in the
section on Self Hosting apps. This is available through the cluster
specific documentation that we make available through the cluster
links. The source for this documentation is available
[here](/cluster/images/docs/src/validator_operator/self_hosting.rst).

### Connecting Locally Hosted Canton Components to a Cluster

It is also possible to connect locally hosted Canton components into
this environment. This includes the REPL, participant nodes, and
domain nodes.  If you don't have Canton, you may install it following the
instructions [here](https://docs.daml.com/canton/usermanual/installation.html).

As part of the runbook a participant node is spun up and connects to
the DevNet domain. Therefore, the runbook contains alternative scripts
for connecting a local participant to the DevNet domain.

### Network Configuration Within Kubernetes

The network configuration we use in our clusters is designed to
satisfy several key requirements:

* Access to our clusters needs to be initially protected from general
  access by the use of a VPN. (As we increase the robustness of our
  software stack, we will remove the requirement for the VPN.)
* The difference in cluster configuration between VPN and non-VPN
  operation should be kept as minimal as possible.
* We must support easy access to our gRPC APIs from web clients.

To accomplish this, we use a Kubernetes `LoadBalancer` as an ingress
to our cluster. This is the exclusive entry point for connections into
the cluster, and is configured with `loadBalancerSourceRanges` that
restrict access to the VPNs listed above.

The `LoadBalancer` is backed by a `Pod` running Nginx and named
`external-proxy`. Each exposed service has an nginx conf file that
redirects a specific port to a given Kubernetes `Service`. The
`Service`s are then backed by `Pod`s that serve the actual API
request. In instances where a gRPC API needs to be made available to a
web client, we also run an `envoy-proxy` sidecar within the `Pod` that
proxies from Web gRPC to gRPC.

## Cluster Tooling

This repository also contains tools for managing clusters hosted in
These clusters run in the Google Cloud, using Google's GKE
implementation of Kubernetes.  The specific configuration for these clusters
is defined in a manifest generated by
[`canton-network-config.jsonnet`](/cluster/manifest/canton-network-config.jsonnet).

All cluster management commands are defined as subcommands of the
`cncluster` script.

### Manual Google Cloud Configuration

Most of the task of setting up clusters is automatic and scripted, but
there are a few aspects that must be manually configured for each GCE
Project through the Google Cloud Console UI.  When a new project is
created within GCE that will host a Canton Network cluster, the
following grants must be made within `da-cn-images` to the default
compute service account within the new cluster.

* The service account must have access to the [Google Artifact Registry](https://console.cloud.google.com/artifacts?&project=da-cn-images) within `da-cn-images`.
* The service account must have Read-Only access to the `release-bundles` Google Storage bucket.

### Docker Image Hosting

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

### Cluster Management Operations

Operations against GCE clusters are complicated by the fact that there
is more than one cluster, and the clusters have different connection
paramaters. To accommodate this, there is a directory under
[`cluster/deployment`](/cluster/deployment) for each cluster that
contains the connection configuration specific to that
cluster. Operations against that cluster must be invoked from within
that directory. This reduces the possibility of operating on the wrong
cluster, and allows the use of the [direnv](https://direnv.net)
mechanism to provide whatever configuration is necessary to identify a
given cluster. The `.envrc` files with cluster identify information
are stored in the deployment directory for the given cluster.

`cncluster help` will provide a full list of supported cluster
subcommands. A few highlights include the following:

* `cncluster apply` - Apply the current working copy's manifest to a
  cluster. The presence of all images referenced by that manifest is
  confirmed prior to application of the manifest.
      * The tag for the images to be deployed can be overridden with an
        optional parameter. If this is specified, then the docker image
        presence check is also bypassed.
      * To docker image check can also be bypassed by setting the
        `CNCLUSTER_SKIP_DOCKER_CHECK` environment variable to 1. This
        can also be added to `.envrc.private`.
* `cncluster check` - Run a series of simple validity checks against the
  external API exposed by a cluster.
* `cncluster create` - Create a new instance of the CN cluster in GCE,
  if it does not already exist.
* `cncluster delete` - Delete the currently running CN cluster from GCE.
* `cncluster deploy` - Build a set of images, push them, and deploy to
  the cluster. This will force push, and overwrite any existing images. The
  intent of this command is to allow developers to bring a cluster to a known
  good state.
* `cncluster info` - Display a table showing all deployed images and resource
  allocation settinos.
* `cncluster ipaddr` - Return the toplevel IP address of the cluster.
* `cncluster logs` - Stream the logs for the specified module running
  in the cluster. This will attempt to apply JSON log formatting,
  unless you specify `--raw`.
* `cncluster ports` - Show a table of all ports exposed from the
  cluster, along with what is on each port.
* `cncluster preflight` - Run the preflight check against the cluster.
* `cncluster push` - Rebuild and push one or more modules into a
  cluster. This command takes care to ensure that the specified modules
  within the cluster are updated to match your local working copy. (It
  also works for base images like `canton` and `cn-app` that do not have
  corresponding cluster modules.)
* `cncluster reset` - Delete all `Pod`s and persistent storage, resetting
  all state and preparing the cluster for a fresh environment startup.
* `cncluster top` - Show memory and CPU usage across the cluster.
* `cncluster wait` - Wait for the clusters' pods to all be noted as in
  a ready state.

Internally, these operations rely on the following environment
variables. As stated above, these are usually populated via `.envrc`.

| Variable Name             | Meaning                                                               |
| ------------------        | --------------------------------------------------------------------- |
| `CLOUDSDK_COMPUTE_REGION` | Google Cloud Region in which resources will be created                |
| `CLOUDSDK_CORE_PROJECT`   | ID of the Google Cloud project in which the cluster is located.       |
| `GCP_CLUSTER_BASENAME`        | Base of the cluster within the cloud project.  Used to compute the cluster's full name and DNS name.                   |
| `GCP_REPO_NAME`           | Google Cloud Project/Name of the image repository used to manage project container images. |

### Manually Deploying via CI

If necessary, it is possible to manually trigger CI/CD deployments to
our production-like clusters. This can be useful if either `DevNet` or
`TestNet` winds up in a bad state or needs a patch.  Before reading
further, there are two caveats to be aware of:

* Our current CI deployment process forces a complete cluster reset,
  including loss of all data.
* These are environments in increasing use by our customers, with
  expectations for uptime and data integrity. Do not reset these
  environments before consulting with the team on Slack.

Given approval, a manual deployment of `main` can be done as follows:

1. Navigate to the CircleCI dashboard for [`main`](https://app.circleci.com/pipelines/github/DACH-NY/the-real-canton-coin?branch=main).
2. Click on "Trigger Pipeline"
3. Add a parameter named `run-job`, with one of the following values:
   * `deploy-devnet` - Reset the state of `DevNet` and deploy a new code set.
   * `deploy-testnet` - Reset the state of `TestNet` and deploy a new code set.
4. Observe progress of the job via the CI console.

#### Confirming the Deployment

To confirm the deployment, you can use a command like the following to
inspect pod state. The `--all-namespaces` flag is necessary because we
now run our clusters with multiple Kubernetes namespaces for various
sub-modules. Without this flag, `kubectl get pods` will skip listing
most of our `Pods`.

`(cd cluster/deployment/devnet && kubectl get pods --all-namespaces)`

This should produce a list of pods, all in running status:

```
 $ kubectl get pods
NAME                                       READY   STATUS    RESTARTS   AGE
docs-856fddb7c8-74k5g                      1/1     Running   0          32m
external-proxy-786cd9c644-59fbz            1/1     Running   0          31m
gcs-proxy-794d475b46-47r5w                 1/1     Running   0          32m
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
a timeout to elapse before attempting to pull the image again. There
can still be pods in `Running` status, due to the fact that we use
Kubernetes deployment objects that wait for an updated pod to be
running before stopping the previous pod. You can look for this
scenario by checking the ages of the running vs. failed pods.

Pods in `Pending` state indicate that they have not yet been scheduled
to run on a `Node`. This can happen as the cluster autoscales upward
to a larger cluster size necessary to accomdate increased load. If a
`Pod` stays in `Pending ` state for more than a few minutes, the
cluster may either be at the upper limit of the autoscaler, or the
memory/CPU request of the `Pod` may be larger than can be accomodated
by any single node.

To skip the image pull backoff timeout, you can delete the failed pod,
which will force an immediate recreation of the pod and attempt to
repull the image.

```kubectl delete pod ${POD_NAME}```

The entire state of the cluster can be reset as follows. This includes
every `Pod` and all of the volumes used to store persistent state. ***If
you do this, all data will be lost.***

```
cncluster reset
```

### Observing Cluster Operation

#### Kubectl and `cncluster` operations.

Run the following commands in the deployment directory of the cluster
you with to observe. For ScratchNet, this is
`cluster/deployment/scratchnet`, and similar for other clsuters.

1. Run `kubectl get pods` to get the status of all pods in the default namespace
   1. `kubectl get pods --all-namespaces` to get the status of all pods, regardless of namespace
   1. `kubectl get pod -n splitwell` to get the status of all pods, in the `splitwell namespace`
1. Run `kubectl get namespace` to get a list of all namespaces
1. Run `kubectl get service,pod,deployment` to list multiple resources with a single command
1. Run `kubectl api-resources` to get a list of Kubernetes object types
1. Run the following command to list *all* resources in the `splitwell` namespace:
   ```
   kubectl api-resources --verbs=list --namespaced -o name \
        | xargs printf '%s,' | sed -e 's/,$//' | xargs -n 1 kubectl get --ignore-not-found -n splitwell
   ```
1. Run `kubectl describe pod <pod-name>` to get a detailed status of
   the given pod, including state transitions that might indicate
   memory or configuratoin failures.
1. Run `cncluster logs` to download application logs
   1. Run `cncluster logs <app-name>` to get the formatted version of
      the JSON log for the given application, regardless of namespace.
      1. Run `cncluster logs --raw <app-name>` to get the raw log for the
         given application, regardless of namespace.
   1. Run `kubectl logs <pod-name> -n <namespace-name>` `kubectl logs
      -l app=<app-name> -n <namespace-name>` to get logs for a given
      pod in a given namespace. (This requires more knowledge than
      `cncluster logs`, but can be useful to query based on time or
      number of lines.)
      1. Add `--tail=-1` to get the complete log snapshot (no limit on the number of lines returned)
      1. Add `-f` to get the live log (new entries streaming to your console)
      1. Add `--since=30m` to only return entries from the past 30min
      1. Add `-p` to get the log from the previous instance. Use this to access the log of a crashed container after it restarted.
1. Run `cncluster gcloud_logs` to download logs from gcloud log
   explorer and open them in lnav. This is useful if the logs are no longer available in
   k8s, e.g., because the cluster got reset. Example:

```
cncluster gcloud_logs validator1 wallet-app 'timestamp>="2023-03-03T11:00:00" AND timestamp<"2023-03-03T11:30:00"'
```

See See https://cloud.google.com/logging/docs/view/logging-query-language for docs on the query language.

1. Run `kubectl get svc` or `cncluster ports` to get an overview of
   ports used within the cluster.
1. Use `lnav` to quickly analyze log files downloaded using `kubectl
   logs` or `cncluster logs`. For JSON format logs, `lnav`
   autodetection requires the log files to have a `.clog` format.
1. If you prefer a web UI to read logs, open the [GCE Log Explorer](#gce-log-explorer).


#### GCE Dashboards

Each of our two GCE projects has a corresponding dashboard that shows
high level stats for the clusters hosted in that project. This
includes information on memory, network, and CPU usage trends over
time:

* [`TestNet`/`DevNet`](https://console.cloud.google.com/monitoring/dashboards/builder/f4d4f86d-7c59-4b27-9a73-fb6e0418e45b?project=da-cn-devnet&dashboardBuilderState=%257B%2522editModeEnabled%2522:false%257D&timeDomain=1m)
* [`Staging`/`ScratchNet`](https://console.cloud.google.com/monitoring/dashboards/builder/ef100871-4e71-409e-a3c2-706b2dbd5465?project=da-cn-scratchnet&dashboardBuilderState=%257B%2522editModeEnabled%2522:false%257D&timeDomain=1m)

#### GCE Log Explorer

Google Cloud offers central log aggregation through its Log Explorer
feature, available here:

* [`TestNet`/`DevNet`](https://console.cloud.google.com/logs/query?project=da-cn-devnet)
* [`Staging`/`ScratchNet`](https://console.cloud.google.com/logs/query?project=da-cn-scratchnet)

Log volumes can be very high, and not all of our current processes
generate logs in the GCE JSON format. This makes it important to use
the log explorer query language to restrict the subset of log messages
to those of interest. Here are a few queries to get you
started. Please feel free to add others you find useful.

The query language itself is documented [here](https://cloud.google.com/logging/docs/view/logging-query-language).

##### Exclude noisy/non-JSON containers

This excludes logs that are either too voluminous to be useful or in
the wrong format for the Google log viewer to correctly handle. This
query can be useful to cull out noise from an overly verbose log stream.


```
-resource.labels.container_name="envoy-proxy"
-resource.labels.container_name="gke-metrics-agent"
-resource.labels.container_name="splitwell-wallet-web-ui"
-resource.labels.container_name="docs"
```

##### Manual configuration actions taken by DA employees

This will show all configuration changes applied by a user with
Digital Asset credentials.

```
protoPayload.authenticationInfo.principalEmail=~"^.*@digitalasset.com$"
protoPayload.serviceName="k8s.io"
```

##### Configuration actions initiated by CircleCI

This will show all configuration changes applied by a user with
Circle CI credentials.

```
protoPayload.authenticationInfo.principalEmail="circleci@da-cn-devnet.iam.gserviceaccount.com"
```

##### Pod error states

This will show all cases where a pod moves into an error state.

```
resource.type="k8s_pod"
resource.labels.location="us-central1"
severity=WARNING
```

##### Check for Cluster Updates

Google will periodically update the software running on a cluster in a
way that disrupts the operation of the cluster. If you see all nodes
in a given cluster's node pool with a younger age than you expect,
this can identity when Google has updated the cluster. (Which involves
restarting all nodes around the same time.)

```
protoPayload.methodName="google.container.internal.ClusterManagerInternal.UpdateClusterInternal"
resource.type="gke_nodepool"
```

##### Check for Autoscaler Activity

This can be used to inspect when the autoscaler scales a cluster up
and down. A scale up is not disruptive to cluster operation, but a
scale down often results in Pod downtime as a node is drained of
running pods to be relocated elsewhere.


```
resource.type="k8s_cluster" AND
log_id("events") AND
(jsonPayload.source.component="cluster-autoscaler" OR jsonPayload.source.component="default-scheduler")
```

#### Canton Ledger Prometheus Metrics

We expose prometheus metrics for our three participants and the domain on the following urls:

- Global Domain: `http://${cluster}.network.canton.global:10313/metrics`
- SVC Participant: `http://${cluster}.network.canton.global:10013/metrics`
- Validator1 Participant: `http://${cluster}.network.canton.global:10113/metrics`
- Splitwell Participant: `http://${cluster}.network.canton.global:10213/metrics`

### Checking Pod Node Assignments and Memory Usage

Kubernetes runs Docker images in `Pod`s of containers that it
schedules to run on `Node`s representing physical hardware (or virtual
machines). The scheduler assigns `Pod`s to `Node`s based on the
resources requested by the `Pod`s containers and the capacity
available on each `Node`. A `Pod` will always be scheduled such that
all of the containers running within that `Pod` are on the same
`Node`. A `Pod` will never be split across more than one `Node`.

Starting in early 2023, we've enabled cluster autoscaling. As a
cluster needs additonal capacity to schedule `Pod`s, the autoscaler
will enlarge the cluster to accomodate the demand. If you see a `Pod`
in a `Pending` state, it means the `Pod` has not been scheduled and
the autoscaler is likely adding a `Node` to accomodate. If a `Pending`
state does not clear up in a few minutes, check the number of `Node`s
against the cluster sizing limits, which currently default to a limit
of eight nodes. If the autoscaler can't add a node, the `Pod` will
never be scheduled and stay in `Pending` state indefinately.

The autoscaler also has the ability to scale the `Node` pool down, if
there is insufficient demand for the capacity. When this happens, the
`Node` will be drained of running `Pod`s, that will then be
rescheduled on the remaining `Node`s. This necessarily means
interruption of the service provided by those `Pod`s, although there
are steps that can be taken to either prevent this from happening to
specific `Pod`s or reduce the likelihood.

To get a summary view of cluster status, you can use `cncluster top`,
which will show a display of cluster usages at both a pod and a node
level. `cncluster info` will show deployed tags and resource requests
made by `Pod`s.

To further investigate `Pod`s in an invalid state, additional details
may be requested through `kubectl describe pod ${POD_NAME}`. This will
show, among many other details, a log of recent events related to the
`Pod`'s deployment within Kubernetes. You can also describe `Node`s to
get details on capacities with `kubectl describe node ${NODE_NAME}`.

## Interacting with a Canton Network Cluster

This section provides step-by-step instructions describing how to
update a cluster in several different development scenarios. To fully
understand these instructions, it is good to understand some of the
fundamentals of [Kubernetes](#appendix:-intro-to-kubernetes).

### Gaining Access to a Cluster

For all cluster interactions, you must be connected to a VPN, as
described in the section above - [Connecting to a Cluster](#connecting-to-a-cluster).

Strictly speaking, the VPN restriction only applies to tasks that
require the Kubernetes (`kubectl`) and application
endpoints. Administration tasks that only need the GCE API (`gcloud`)
can be done without being logged into the VPN. Given that the dividing
line between `kubectl` and `gcloud` API's can be difficult to
ascertain, it's better not to rely on this fact in your daily
work. However, it can be useful to understand this distinction when
encounting certain errors that might occur if the VPN fails during a
long operation.


#### Fixing Connection Issues in kubectl

Occasionally, you might encounter difficulty accessing `kubectl`, even
if the VPN is connected. If `kubectl get pods` times out for a given
cluster and you know you are connected to the VPN, it may be necessary
to force `.kubecfg` to be regenerated. This can be required if someone
else has fully rebuilt the cluster. (`cncluster delete`/`cncluster
create`)

To do so run the following commands from the cluster directory,
e.g. `cluster/deployment/staging`:

```
cncluster activate
```

### Deploy a Build to a Cluster

1. Scratchnet is used for ad-hoc testing, and we have a limited number
   of shared instances. To claim a cluster for your use, run `cncluster lock`
   from the cluster's deployment directory, which will then assert a lock on
   the cluster in your name (unless somebody else has it already).
1. Build and upload all docker images
    1. Clean and build the main application, by invoking `make clean`
       and `make build` from the project root.
    1. From a cluster deployment directory, run `cncluster
       deploy`. This will rebuild the images and apply the manifest to
       the current cluster.
    1. If you still run into any issues, run `make clean-all` from the
        project root directory to clear all state, and try again.
1. Debug your deployment. Tools mentioned in [Observing Cluster Operation](#observing-cluster-operation)
   can be useful.
1. Once you are done with scratchnet, release the cluster lock with
   `cncluster unlock`. Unless you're handing the cluster state over
   to someone else, reset the cluster with `cncluster reset`. This
   will make it easier for the next person, and reduce cloud costs.

### Update a Single Component in a Cluster

1. Scratchnet is used for ad-hoc testing, and we have a limited number
   of shared instances. To claim a cluster for your use, run `cncluster lock`
   from the cluster's deployment directory, which will then assert a lock on
   the cluster in your name (unless somebody else has it already).
1. If ScratchNet is not in a running state, follow the instructions
   above to ensure it's running a valid code set. This should ideally
   be done against the HEAD of `main` to give the best chance of
   getting to a working state.
1. From a deployment directory, invoke `cncluster push`, passing in
   the module names for the modules you wish to update. This will
   start a build for those modules, push the resulting images to the
   [Google Artifact Registry](https://console.cloud.google.com/artifacts?&project=da-cn-images).
   and patch all necessary `Deployment` objects with
   the image tag to force an update.
1. Debug your deployment. Tools mentioned in [Observing Cluster Operation](#observing-cluster-operation)
   can be useful.
1. Once you are done with scratchnet, release the cluster lock with
   `cncluster unlock`. Unless you're handing the cluster state over
   to someone else, reset the cluster with `cncluster reset`. This
   will make it easier for the next person, and reduce cloud costs.

### Add a Component to the Build

1. Edit the canton network cluster definition
    1. If you need to add a new asset (e.g., a new frontend bundle):
       make sure that asset is included in the bundle produced by `sbt bundle`.
       That release bundle is included in the `./cluster/images/cn-app` image,
       which is used by many of our other images.
    1. If you need to edit an existing cluster component
       (e.g., change the config of some app backend):
       edit the corresponding folder in `./cluster/images`.
       Each component defines its own docker image.
    1. If you need to add a new component:
        1. Add a new folder to `./cluster/images`
           1. Make sure the new folder contains a Dockerfile and a `local.mk` file.
              When in doubt, start by duplicating an existing component.
        2. Edit `cluster/images/local.mk`, adding the component at the end of the existing list of components
           1. Note that the order matters, if the image of your component depends on the image of another component,
              your component must be listed after the dependency.
        1. Edit `./cluster/manifest/canton-network-config.jsonnet`, adding the new component to the `cantonNetwork()` function
        1. If the new component has an API that should be reachable from the internet,
           add a new config file to `./cluster/images/external-proxy/conf`
    1. Note that you are responsible for making sure the ports defined in different config files are consistent.
       In particular, consider:
       1. `./cluster/manifest/canton-network-config.jsonnet` (ports used within the cluster)
       1. `./cluster/images/external-proxy/config` (egress of the cluster)
       1. config files baked into individual component images
       (ports that the applications actually use)
1. If you touched `./cluster/manifest/canton-network-config.jsonnet`,
   run `make cluster/manifest/test-update`

### Memory Settings

The most commonly used memory settings for the cluster (Postgres and the ledger)
are stored in [`network-settings.json`](./manifest/network-settings.json) and
may be adjusted there. There are also settings for the network whitelist.

## TLS Certificate Provisioning

### First-time Infra Setup

Certificates are issued and renewed in the cluster automatically by `cert-manager`. There is some setup to configure specific versioned releases of `cert-manager` ready for deployment in our clusters.

In particular, because we operate a private GKE cluster, we need to mirror `cert-manager`'s images to our internal image repository. We also need to rewrite the `cert-manager` manifest to point to our mirror instead of the default `quay.io` hosted images.

Both of these actions can be executed automatically by the `update-cert-manager.sh` script located in the `manifest/cert-manager` directory.

Currently we run `cert-manager` v1.10.0, the latest stable release at time of writing. The update script doesn't have to be re-run unless a new version of `cert-manager` is released and upgrading to it is critical, such as for addressing security vulnerabilities.

### Cluster Configuration

When the `cert-manager.yaml` manifest is applied to a cluster, a couple of deployments are started up in the `cert-manager` namespace. These services carry out the process of requesting and storing certificates when the appropriate custom kubernetes resources are created.

Use `kubectl get pods --namespace cert-manager` to check on the status of these deployments.

Of note, we create an `Issuer` resource in our `cluster.jsonnet` manifest that contains configuration for the entity that will issue us certs. We also create a `Certificate` resource to request the cert itself and declare the secret to store it in.

Use `kubectl get issuers` and `kubectl get certificates` to check the status of these resources.

Finally, we set up `external-proxy`, our nginx cluster ingress, to be ready to serve as a TLS termination point. This is achieved by mounting the certificate from the secret into the filesystem, and referencing the certificate from within the nginx config.

### Adding TLS to {insert-service-here}

Ultimately your service is likely being proxied through some `external-proxy` config file. The only thing required to enable TLS termination for a new service is to add a block like

```
server {
   listen     443 ssl;
   listen [::]443 ssl;

   ssl_certificate     /tmp/tls.crt;
   ssl_certificate_key /tmp/tls.key;

   ...
}
```

You can expect the certificate and certificate key to always be available at `/tmp/tls.crt` and `/tmp/tls.key` respectively, via the kubernetes secret volume mount mentioned above.

### Force-updating the certificate

The tls certificate is configured in
[`tls.jsonnet`](/cluster/manifest/tls.jsonnet). If changes are required there, e.g. updating the DNS names covered it, you will need to propagate a new certificate in all clusters. To do that, follow these steps:
```
kubectl get certificate
kubectl delete certificate cn-<cluster>-certificate
kubectl get secret
kubectl delete secret cn-<cluster>-tls
cncluster apply
```

## Auth0 secrets

Our apps need some secrets in order to interact with auth0.
In order to update these secrets, please set the environment variables `AUTH0_MANAGEMENT_API_CLIENT_ID`
and `AUTH0_MANAGEMENT_API_CLIENT_SECRET` to the client id and secret of the
[Auth0 Management API Explorer](https://manage.auth0.com/dashboard/us/canton-network-dev/apis/management/explorer).
It is recommended you store this in `.envrc.private`.

In order to update secrets on a deployed cluster, run `cncluster update_secrets`. It will fetch all
relevant secrets from Auth0 using the management API token obtained above, and store them in
kubernetes secrets.

## Participant User Configuration

At least one user needs to be allocated as part of the bootstrap file
of a participant for bootstrapping. The configuration for those users
is specified through a `CANTON_PARTICIPANT_USERS` environment
variable. That variable specifies an array of users in JSON format
that will be allocated in the given order. Each user specifies the
user name, the primary party which can either be taken from another
user or allocated freshly as well as `actAs`, `readAs` and `admin`
claims. `actAs` and `readAs` claims can also be set to the primary
party of another user. References can go through environment variables
which allows us to pick up k8s secrets which are exposed through other
environment variables. Using the SVC participant as an example, here
is how the SVC user, Scan user and Directory user are specified. Note
how the Scan and Directory users share their primary party and
`actAs`/`readAs`with the SVC user.

```json
[
  {
    name: { env: "CN_APP_SVC_LEDGER_API_AUTH_USER_NAME" },
    primaryParty: { allocate: "svc_party" },
    actAs: [{ fromUser: "self" }],
    readAs: [],
    admin: true,
  },
  {
    name: { env: "CN_APP_SCAN_LEDGER_API_AUTH_USER_NAME" },
    primaryParty: { fromUser: { env: "CN_APP_SVC_LEDGER_API_AUTH_USER_NAME" } },
    actAs: [],
    readAs: [{ fromUser: { env: "CN_APP_SVC_LEDGER_API_AUTH_USER_NAME" } }],
    admin: false,
  },
  {
    name: { env: "CN_APP_DIRECTORY_LEDGER_API_AUTH_USER_NAME" },
    primaryParty: { fromUser: { env: "CN_APP_SVC_LEDGER_API_AUTH_USER_NAME" } },
    actAs: [{ fromUser: { env: "CN_APP_SVC_LEDGER_API_AUTH_USER_NAME" } }],
    readAs: [],
    admin: true,
  }
]
```

The exact JSON format is defined in `tools.sc`.

## Token configuration

By default, our apps are configured using client ids and secrets and
use a client credentials flow to request tokens from Auth0 on
startup. However, auth0 has limits on how many tokens can be requested
per month. To work around this, we configure our staging cluster
(which is redeployed most frequently) with a fixed static token which
is refreshed daily.

The behavior can be switched by setting the following environment variable, e.g., to test this on scratchnet.

```
export CNCLUSTER_FIXED_TOKENS=1
```

After setting that first refresh the secrets. This will not set client
id and secret but instead query auth0 for an m2m token and set that:

```
cncluster update_secret
```

After that, you can now apply the manifest (while still keeping the environment variable set).

```
cncluster apply
```

After you finished testing, make sure to reset scratchnet back to the
default behavior by unsetting the environment variable and updating
the secrets:

```
unset CNCLUSTER_FIXED_TOKENS
cncluster update_secrets
```

## Pulumi and Helm

Canton Network is currently deployed by applying a manifest generated
using Jsonnet to a GKE cluster via `kubectl apply`. To accommodate
packaging needs for our customers, we are switching over to a Helm
chart based deployment strategy that's managed using Pulumi
scripts. While this work is not complete, the beginnings of it are
already committed and are available as a prototype for testing.

The current Pulumi deployment is in an interim state, and can manage
the cluster ingress and documentation server. Note that in the
short-run, you'll need to manually reduce the number of endpoints
expected in `external-proxy` for that service to correctly boot.

1. Start with a working cluster and change to its deployment directory.
1. Delete the existing cluster resources: `cncluster obliterate_state`.
1. Apply the Pulumi cluster (you will need to enter a passkey, even
   though it is currently not used): `cncluster papply`.
1. Use `kubectl get pods -A` to observe creation of the four new SV App nodes.
1. The Pulumi and Helm charts may now be edited and `cncluster papply`
   once again used to apply only the changes to the cluster.
1. `cncluster pulumi down` Will remove from the cluster, the portions
   of the configuration managed by Pulumi.

## Appendix: Kubernetes Resources

* Kubernetes Documentation
   * [Fundamentals of Kubernetes](https://kubernetes.io/docs/concepts/overview/).
   * [`kubectl` Documentation](https://kubernetes.io/docs/reference/kubectl/)
   * [Liveness and Readiness Probes](https://kubernetes.io/docs/tasks/configure-pod-container/configure-liveness-readiness-startup-probes/)
   * [`Deployment`](https://kubernetes.io/docs/concepts/workloads/controllers/deployment/)
   * [`Service`](https://kubernetes.io/docs/concepts/services-networking/service/)
* [Object model API reference](https://kubernetes.io/docs/reference/generated/kubernetes-api/v1.22)
* [Google on Cloud Readiness](https://cloud.google.com/blog/products/devops-sre/want-repeatable-scale-adopt-infrastructure-as-code-on-gcp)

### Manifests

To make it easier to apply configurations in bulk, Kubernetes has the
concept of a manifest. A manifest is a set of JSON or YAML object
definitions that represent the overall configuration of an application
within a Kubernetes cluster.  The configuration of a Canton Network
cluster is defined in terms of a manifest we generate by a [Jsonnet](http://jsonnet.org)
script: [`canton-network-config.jsonnet`](/cluster/manifest/canton-network-config.jsonnet).

This produces a manifest that describes all the objects necessary
to run the Canton Network specific parts of our clusters.

To simplify tracking of changes and make it easier to reliably refactor
our manifest generation scripts, we use a
[characterization test](https://en.wikipedia.org/wiki/Characterization_test)
approach. With this strategy, we check in the output of running our manifest
generator against a standard set of inputs. For a build to pass, the manifest
generation script must produce output that matches this standard output. If
there are changes to the output, they will necessarily be directly reviewed
as part of the PR review process.
