# Table of Contents

- [Table of Contents](#table-of-contents)
  - [Available Clusters](#available-clusters)
  - [Connecting to a Cluster](#connecting-to-a-cluster)
    - [Granting VPN Access to External Partners](#granting-vpn-access-to-external-partners)
    - [Available Cluster Services](#available-cluster-services)
    - [Connecting Locally Hosted Canton Network Apps to a Cluster](#connecting-locally-hosted-canton-network-apps-to-a-cluster)
    - [Connecting Locally Hosted Canton Components to a Cluster](#connecting-locally-hosted-canton-components-to-a-cluster)
    - [Network Configuration Within Kubernetes](#network-configuration-within-kubernetes)
  - [Cluster Tooling](#cluster-tooling)
    - [Manual Google Cloud Configuration](#manual-google-cloud-configuration)
    - [Docker Image Hosting](#docker-image-hosting)
    - [Cluster Management Operations](#cluster-management-operations)
    - [Manually Deploying via CI](#manually-deploying-via-ci)
      - [Confirming the Deployment](#confirming-the-deployment)
    - [Observing Cluster Operation](#observing-cluster-operation)
      - [Kubectl and `cncluster` operations.](#kubectl-and-cncluster-operations)
      - [GCE Dashboards](#gce-dashboards)
      - [GCE Log Explorer](#gce-log-explorer)
        - [Exclude noisy/non-JSON containers](#exclude-noisynon-json-containers)
        - [Manual configuration actions taken by DA employees](#manual-configuration-actions-taken-by-da-employees)
        - [Configuration actions initiated by CircleCI](#configuration-actions-initiated-by-circleci)
        - [Pod error states](#pod-error-states)
        - [Check for Cluster Updates](#check-for-cluster-updates)
        - [Check for Autoscaler Activity](#check-for-autoscaler-activity)
      - [Canton Ledger Prometheus Metrics](#canton-ledger-prometheus-metrics)
      - [Alerts](#alerts)
    - [Checking Pod Node Assignments and Memory Usage](#checking-pod-node-assignments-and-memory-usage)
    - [Managing GKE Kubernetes Versions](#managing-gke-kubernetes-versions)
  - [Interacting with a Canton Network Cluster](#interacting-with-a-canton-network-cluster)
    - [Gaining Access to a Cluster](#gaining-access-to-a-cluster)
      - [Fixing Connection Issues in kubectl](#fixing-connection-issues-in-kubectl)
    - [Cluster Infrastructure Setup](#cluster-infrastructure-setup)
    - [Deploy a Build to a Cluster](#deploy-a-build-to-a-cluster)
    - [Add a Component to the Build](#add-a-component-to-the-build)
    - [Modifying a Deployed Cluster](#modifying-a-deployed-cluster)
    - [Memory Settings](#memory-settings)
  - [TLS Certificate Provisioning](#tls-certificate-provisioning)
    - [Adding TLS to {insert-service-here}](#adding-tls-to-insert-service-here)
    - [Force-updating the certificate](#force-updating-the-certificate)
  - [Auth0 secrets](#auth0-secrets)
  - [Participant User Configuration](#participant-user-configuration)
  - [Token configuration](#token-configuration)
  - [Testing the SV Helm Runbook](#testing-the-sv-helm-runbook)
  - [SV Operations](#sv-operations)
    - [Approving new SVs](#approving-new-svs)
      - [Approving via SV API](#approving-via-sv-api)
      - [Approving via SV config](#approving-via-sv-config)
  - [Configuring a New GCP Project](#configuring-a-new-gcp-project)
  - [Cluster Data Dumps](#cluster-data-dumps)
      - [Test and CircleCI setup](#test-and-circleci-setup)
      - [Pruning Data Dumps](#pruning-data-dumps)
      - [Bootstrapping from a Cluster Data Dump](#bootstrapping-from-a-cluster-data-dump)
  - [Appendix: Kubernetes Resources](#appendix-kubernetes-resources)
    - [Manifests](#manifests)

Note that operations in this directory require authentication to use
Google Cloud APIs. If you have `direnv` installed (which you should),
you will be asked to authenticate when you change into this directory
for the first time.

## Available Clusters

The global Canton Network clusters are currently hosted in Google
Cloud. There are multiple clusters, each with a different purpose, all
of which are accessible only through VPN:

| Cluster         | URL                                       | Deployment Policy                | Purpose                                |
|-----------------|-------------------------------------------|----------------------------------|----------------------------------------|
| TestNet         | http://test.network.canton.global         | Weekly, Midnight UTC Sunday      | Longer Running Tests                   |
| DevNet          | http://dev.network.canton.global          | Nightly, 6AM UTC                 | Current, Tested `main`                 |
| CIDaily         | http://cidaily.network.canton.global      | Nightly, 6AM UTC                 | Current, Tested `main`                 |
| Staging         | http://staging.network.canton.global      | After every push to `main`       | Latest `main`                          |
| ScratchNetA     | http://scratcha.network.canton.global     | Ad hoc, manual                   | Cluster Configuration Development      |
| ScratchNetB     | http://scratchb.network.canton.global     | Ad hoc, manual                   | Cluster Configuration Development      |
| ScratchNetC     | http://scratchc.network.canton.global     | Ad hoc, manual                   | Cluster Configuration Development      |
| ScratchNetD     | http://scratchd.network.canton.global     | Ad hoc, manual                   | Cluster Configuration Development      |
| ScratchNetE     | http://scratche.network.canton.global     | Ad hoc, manual                   | Cluster Configuration Development      |
| ScratchNetF     | http://scratchf.network.canton.global     | Ad hoc, manual                   | Cluster Configuration Development      |
| ScratchNetG     | http://scratchg.network.canton.global     | Ad hoc, manual                   | Cluster Configuration Development      |
| TestNet Preview | http://test-preview.network.canton.global | Ad hoc, through CI               | Longer Running Tests with devnet=false |

The automatic deployments are configured as
[Scheduled](https://app.circleci.com/settings/project/github/DACH-NY/canton-network-node/triggers?return-to=https%3A%2F%2Fapp.circleci.com%2Fpipelines%2Fgithub%2FDACH-NY%2Fcanton-network-node)
[CI/CD](/.circleci/config.yml) in CircleCI.

The `ScratchNetX` clusters are manually managed and intended to be
test beds for new code and deployment process updates. These are a
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
support. Partner access through this VPN is only to global Canton Network
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
request. To make the ledger API available to web frontends, we
enable the JSON API support in our Canton participants.

## Cluster Tooling

This repository also contains tools for managing clusters hosted in
Google Cloud, running Google's GKE implementation of Kubernetes.  The
specific configuration for these clusters is defined in a combination of
[Pulumi scripts](/cluster/pulumi) and [Helm charts](/cluster/helm).

All cluster management commands are defined as subcommands of the
`cncluster` script, and are written in terms of Pulumi charts.

## Pulumi and Helm

Canton Network is generally deployed by installing a collection of
Helm charts into a Kubernetes cluster. For internally managed
clusters, we automate this process through the use of Pulumi scripts.

As of the time of this writing, there are two separate Pulumi
projects. The lower level of the two is an
[infrastructure project](#cluster-infrastructure-setup) that's
already in use to manage our cluster's IP addresses, DNS entries,
and TLS certificates, and other of the more static aspects of our
cluster configuration. The higher level of the two is the
[`canton-network`](pulumi/canton-network) script. This script
uses our redistributable Helm charts to install a Canton Network
test environment inside a cluster already configured with the
infrastructure script.

1. Start from a clean slate: `make -C $REPO_ROOT clean`
   - Note that this step internally calls `sbt bundle` to build the most recent version of our apps.
     later steps don't call `sbt bundle` automatically, as it takes too long.
1. Ensure docker images are built and pushed to the Docker repository: `make -C $REPO_ROOT docker-push -j`
   - Note: helm charts built locally reference the docker images using just your username.
     Make sure to `make docker-push`, whenever you want to propagate local changes.
1. Start with a working cluster and change to its deployment directory.
1. Acquire the cluster lock `cncluster lock`.
1. Delete the existing cluster resources managed by pulumi: `cncluster reset`.
   This should typically not be required as the cluster is reset upon unlocking it.
1. Apply the Pulumi cluster configuration: `cncluster apply`.
   - Use `kubectl get pods -A` to observe creation of the four new SV App nodes.
   - You can also use the graphical `k9s` tool for this purpose, see its [docs here](https://k9scli.io/).
   - Some tips for handling deployment failures:
     - *Secrets not containing the right values*: decode the secret using something like
       `kubectl get secret -n sv-1 cn-gcp-bucket-da-cn-devnet-da-cn-data-dumps -o 'jsonpath={.data.json-credentials}' | base64 -d`
       or use `k9s` to navigate to the secrets overview using `:secrets` and press `x` on the secret of interest.
     - *Cancelled pulumi holding the lock*: release the lock using `cncluster pulumi canton-network cancel`
     - See also the section on [Modifying a Deployed Cluster](#modifying-a-deployed-cluster)
1. The Pulumi and Helm charts may now be edited and `cncluster apply`
   once again used to apply only the changes to the cluster.
1. Release the cluster lock `cncluster unlock`, which also resets the cluster.


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
parameters. To accommodate this, there is a directory under
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

* `cncluster apply` - Apply the current working copy's `canton-network`
  Pulumi stack to a cluster. The presence of all images referenced by that
  configuration is confirmed prior to application of the manifest.
      * The tag for the images to be deployed can be overridden with an
        optional parameter. If this is specified, then the docker image
        presence check is also bypassed.
      * To docker image check can also be bypassed by setting the
        `CNCLUSTER_SKIP_DOCKER_CHECK` environment variable to 1. This
        can also be added to `.envrc.private`.
* `cncluster pdown` - Take down any installed resources populated with
  the `canton-network` Pulumi stack.
* `cncluster create` - Create a new instance of the CN cluster in GCE,
  if it does not already exist.
* `cncluster delete` - Delete the currently running CN cluster from GCE.
* `cncluster info` - Display a table showing all deployed images and resource
  allocation settings.
* `cncluster ipaddr` - Return the toplevel IP address of the cluster.
* `cncluster logs` - Stream the logs for the specified module running
  in the cluster. This will attempt to apply JSON log formatting,
  unless you specify `--raw`.
* `cncluster preflight` - Run the preflight check against the cluster.
* `cncluster preflight_sv` - Run the SV preflight check against the cluster.
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

`cncluster` also has basic autocompletion for bash. To install that, add a line: `source <this script>` to your ~/.bashrc

### DevNet and TestNet

The DevNet cluster is updated every day with the latest code from
`main`. The Wednesday night release into DevNet is considered the
release candidate for the upcoming weekend deployment into TestNet. If
the Wednesay DevNet release passes its preflight test, the commit is
marked with the `testnet-next` tag and deployed to TestNet the
upcoming week. This allows the TestNet releases to be driven by our
Wednesday to Wednesday weekly sprint cycle and adequately tested
before being deployed to customers in TestNet.

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

1. Navigate to the CircleCI dashboard for [`main`](https://app.circleci.com/pipelines/github/DACH-NY/canton-network-node?branch=main).
2. Click on "Trigger Pipeline"
3. Add a parameter named `run-job`, with one of the following values:
   * `deploy-devnet` - Reset the state of `DevNet` and deploy a new code set.
   * `deploy-testnet` - Reset the state of `TestNet` and deploy a new code set.
   * `deploy-testnet-preview` - Reset the state of `TestNet Preview` and deploy a new code set.
4. When deploying a network that bootstraps from ACS and participant identities dumps (such as `TestNet` and `TestNet Preview`), you might need to override the default bootstrapping config using an additional `bootstrapping-config` parameter. See [Bootstrapping from a Cluster Data Dump](#bootstrapping-from-a-cluster-data-dump).
5. Observe progress of the job via the CI console.

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
you want to observe. For `ScratchNetA`, this is
`cluster/deployment/scratchneta`, and similar for other clsuters.

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

1. Run `kubectl get svc` to get an overview of ports used within the
   cluster.
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
-resource.labels.container_name="gke-metrics-agent"
-resource.labels.container_name="splitwell-wallet-web-ui"
-resource.labels.container_name="docs"
```

You can also use patterns to exclude similarly named nodes and containers, for instance:

```
-resource.labels.node_name=~"gke-cn-devnet-default-pool-.*"
-resource.labels.container_name=~"cometbft.*"
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

#### Alerts

We have configured alert policies on Google Cloud for our `devnet`, `testnet`, and `testnet-preview` clusters.
Our policies can be configured and extended via the
[Google alerting dashoard](https://console.cloud.google.com/monitoring/alerting?project=da-cn-devnet).
When an alert triggers, we are notified over Slack on the `#team-canton-network-internal-ci` channel.
At the time of writing, we have only configured alerts on unexpectedly high CPU usage.

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

### Managing GKE Kubernetes Versions

Each Canton Network cluster is running a specific version of the
Google Kubernetes Engine software. The current versions for all
clusters in a Google Cloud project can be inspected by running the
following command in a cluster deployment directory:

```
 ~/work/canton-network-node/cluster/deployment/scratchneta (main|✚2…) $ gcloud container clusters list
NAME            LOCATION     MASTER_VERSION   MASTER_IP        MACHINE_TYPE    NODE_VERSION     NUM_NODES  STATUS
cn-scratchanet  us-central1  1.27.2-gke.1200  104.154.141.251  e2-standard-16  1.27.2-gke.1200  4          RUNNING
cn-scratchbnet  us-central1  1.27.2-gke.1200  104.198.193.216  e2-standard-16  1.27.2-gke.1200  4          RUNNING
cn-scratchcnet  us-central1  1.27.2-gke.1200  34.133.202.82    e2-standard-16  1.27.2-gke.1200  4          RUNNING
cn-scratchdnet  us-central1  1.27.2-gke.1200  34.31.85.189     e2-standard-16  1.27.2-gke.1200  3          RUNNING
cn-scratchenet  us-central1  1.27.2-gke.1200  34.28.246.100    e2-standard-16  1.27.2-gke.1200  3          RUNNING
```

This command shows the status of all clusters in the current cluster's
project. The `MASTER_VERSION` and `NODE_VERSION` column shows the
master and node versions, respectively.  These columns will displayed
with one or more asterisks (`*`) if there is an upgrade available.

The current cluster may be upgraded to the latest version with the
`cncluster upgrade` subcommand. This command will upgrade first the
cluster `master` version, followed by the `node` version. Note that
this operation will result in downtime and can take up to 20-30
minutes depending on the number of nodes to upgrade.

`cncluster upgrade` will not upgrade either the master or node
versions through more than a single minor revision. An upgrade from
`1.25` to `1.27` must be manually stepped from `1.25` to `1.26` and
then to `1.27`. Clusters should not get this far out of date, so
`cncluster upgrade` does not automatically support this
scenario. Remediation of a cluster in this state must be done through
manual invocations of `gcloud container clusters upgrade`, specfiying
`--cluster-version` explicitly.

#### Automatic GKE Cluster Upgrades

Google Kubernetes Engine will occasionally automatically upgrade
clusters running old versions. This will result in cluster downtime
that can be difficult to explain. There are a few tools that can be
used to identify this scenario.  The first is to interrogate the
cluster node ages:

```
$ kubectl get nodes
NAME                                            STATUS   ROLES    AGE     VERSION
gke-cn-scratchfnet-default-pool-cbb2910f-cpck   Ready    <none>   3h8m    v1.27.2-gke.1200
gke-cn-scratchfnet-default-pool-cbb2910f-kok9   Ready    <none>   3h16m   v1.27.2-gke.1200
gke-cn-scratchfnet-default-pool-cbb2910f-y29z   Ready    <none>   3h12m   v1.27.2-gke.1200
gke-cn-scratchfnet-default-pool-cbb2910f-zunr   Ready    <none>   3h10m   v1.27.2-gke.1200
```

Node version upgrades tend to replace all the nodes at the same
time. This will result in node ages that are lower than expected for
the life of the cluster, and likely uniformly about the same across
the node pool. (There can be deviation in node ages over time as nodes
are created and deleted by the autoscaler.)

The other and more direct way of identifying a cluster upgrade is
through the Google Cloud operations list. This is an example log
showing a series of recent upgrades in some of the scratch clusters:

```
$ gcloud container operations list|grep UPGRADE_
operation-1690830948095-cac0f69e-9c0b-4292-9216-ef6c25a1b765  UPGRADE_MASTER  us-central1  cn-scratchgnet                  DONE    2023-07-31T19:15:48.095147203Z  2023-07-31T19:38:36.486744452Z
operation-1690835857405-1b29a13a-ddb6-4ae1-ab9c-60ee9d8da138  UPGRADE_NODES   us-central1  default-pool                    DONE    2023-07-31T20:37:37.405054101Z  2023-07-31T21:47:55.486570429Z
operation-1690885493095-908d0c29-1f7d-41c3-83fc-3810bc78643a  UPGRADE_MASTER  us-central1  cn-scratchfnet                  DONE    2023-08-01T10:24:53.095303259Z  2023-08-01T10:46:23.959101752Z
operation-1690885504217-abbe13e1-3e1b-447d-b59d-2113969b85fa  UPGRADE_MASTER  us-central1  cn-scratchgnet                  DONE    2023-08-01T10:25:04.217070453Z  2023-08-01T10:47:42.387391399Z
operation-1690887287480-fed64b27-241c-4f3f-a492-7d3f9d53eca5  UPGRADE_NODES   us-central1  default-pool                    DONE    2023-08-01T10:54:47.480812409Z  2023-08-01T12:05:15.994131281Z
operation-1690887294973-34a6ec25-6d29-49ce-b771-574603637883  UPGRADE_NODES   us-central1  default-pool                    DONE    2023-08-01T10:54:54.973619067Z  2023-08-01T12:05:15.728995605Z
operation-1690891915658-4ab66047-30f9-40cf-adeb-24f3b8c2d951  UPGRADE_NODES   us-central1  default-pool                    DONE    2023-08-01T12:11:55.65858791Z   2023-08-01T12:12:08.946926319Z
operation-1690891925062-1cedc8ed-a575-479c-b68f-288e7365ad01  UPGRADE_NODES   us-central1  default-pool                    DONE    2023-08-01T12:12:05.062283706Z  2023-08-01T12:12:15.930049195Z
```

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
else has fully rebuilt the cluster. (`cncluster delete`/`cncluster create`)

To do so run the following commands from the cluster directory,
e.g. `cluster/deployment/staging`:

```
cncluster activate
```

### Cluster Infrastructure Setup

We manage cluster infrastructure using a Pulumi stack separately
applied to each cluster. This stack manages aspects of a cluster's
configuration that have a longer lifecycle than any one deployment of
the Canton Network software. This configuration includes the cluster
ingress IP address, DNS records, and the certificate used for incoming
traffic. From within a deployment directory for a cluster, this stack
can be managed with `cncluster pulumi infra`. The following commands
cover the typical lifecycle of a Canton Network cluster.

* `cncluster pulumi infra up` - Apply the infrastructure configuration
  to the cluster..
* `cncluster pulumi infra down` - Remove the configured infrastructure
  for the cluster.
* `cncluster pulumi infra refresh` - Refresh Pulumi's infrastructure
  state database based on the current cluster infrastructure. This
  is useful when a cluster configuration is updated externally.

### Deploy a Build to a Cluster

1. The scratchnet clusters are used for ad-hoc testing, and we have a limited
   number of instances, shared across the team. To claim a cluster for your use,
   run `cncluster lock`  from the cluster's deployment directory, which will
   then assert a lock on the cluster in your name (unless somebody else has
   it already).
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
1. If you do not release the lock on a cluster, the lock reaper will
   automatically do so once it notices a lock has been held for more
   than six hours. The reaper process is run hourly.
1. If you are confident a cluster is no longer in use, it is also
   possible to forcibly remove someone else's lock with
   `cncluster unlock_force`. This should be done only as a last
   resort, and only in the event you are absolutely sure the cluster
   is no longer in use. If you do forcibly remove a cluster lock,
   please be sure to let the former lockholder know.

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
        1. Decide on a deployment strategy for the new component. It
           should either be added to an existing Helm chart if it is
           intrinsic to the functionality of that module, or a new
           Helm chart should be created for the new component.
        1. If necessary, edit either the test cluster Pulumi stack (`./cluster/puluni/canton-network/`)
           or SV Pulumi stack (`./cluster/puluni/sv-runbook/`) to incorporate the new Helm chart.
        1. If you have modified the SV Runbook Pulumi stack, keep in mind the fact that Pulumi
           stack is intended to reflect the runbook documentation in
           `./cluster/images/docs/src/sv_operator/sv_helm.rst`. If you've modified the stack,
           you will also likely need to modify the documentation for our customers.
        1. If the new component has an API that should be reachable from the internet,
           ensure that the approprate ingress modules contain proxy definitions for
           that API. For test cluster components, the ingress definition will be in
           `./cluster/images/external-proxy-full/conf`.
    1. Note that you are responsible for making sure the ports defined in different config files are consistent.
       In particular, consider:
       1. `./cluster/images/external-proxy-full/config` (egress of the cluster)
       1. `./cluster/images/external-proxy-sv/config` (egress of the SV Runbook configuration)
       1. config files baked into individual component images
       (ports that the applications actually use)
1. If you touched any of the Helm charts or Pulumi stacks, you will need to update the
   characterization tests for our deployment with `make cluster/pulumi/update-expected`

### Modifying a Deployed Cluster

For certain cases, local modifications may be applied to a cluster by simply rerunning `cncluster apply`.
An extreme option if that does not work is simply `cncluster pdown && cncluster apply`,
however that takes several minutes and is hard to iterate with.

It is often much more convenient to experiment with modifications directly on the deployed resources, via `kubectl edit`, or `e` in `k9s`.

Alternatively, for changes in a Helm chart, or in the values with which it is deployed, also consider one of the following options:

To uninstall a single Helm chart, and have Pulumi reinstall it with whatever modifications you made locally:
1. Run `helm list -A` to see a list of all deployed Helm chart in all namespaces, and find the one of interest
1. Uninstall the current Helm chart using `helm uninstall -n <namespace> <name>`, for example `helm uninstall -n sv-1 sv-1-sv-app
1. Run `cncluster prefresh` to get Pulumi to refresh its state against that of the cluster
1. Run `cncluster apply` to get Pulumi to reinstall the uninstalled chart.

Alternatively, you can also modify an installed chart, e.g. to change the values with which it is installed, or simply to reinstall it to apply local changes to the chart:
1. Run `helm list -A` to see a list of all deployed Helm chart in all namespaces, and find the one of interest
1. Run `helm get values -n <namespace> <name> > vals.yaml` to get the values with which the chart is currently installed
1. Edit `vals.yaml`: delete the first line ("USER-SUPPLIED VALUES:"), and modify whatever values you with to change
1. Run `helm upgrade -n <namespace> <name> $REPO_ROOT/cluster/helm/target/<your-helm-chart>.tgz -f vals.yaml


### Memory Settings

The most commonly used memory settings for the cluster (Postgres and the ledger)
are stored in [`network-settings-devnet.json`](./network-settings-devnet.json) and [`network-settings-non-devnet.json`](./network-settings-non-devnet.json)
These memory settings may be adjusted there. There are also settings for the network whitelist.

## TLS Certificate Provisioning

Certificates are issued and renewed in the cluster automatically by
`cert-manager`. `cert-manager` is installed by the infrastructure
Pulumi scripts as described above and configured through Kubernetes
CRD objects installed with the same Pulumi script.

`cert-manager` installs the the TLS certificate into a Kubernetes
secret that is then mounted into the `external-proxy` Pod for use by
`nginx` to terminate inbound HTTPS. The state of the certificate
acquisition process is modeled in several additional Kuberneted CRD
types that can be inspected with `kubectl`.

If there is an issue acquiring a certificate, the first obvious
symptom is usually that `external-proxy` gets stuck in
`ContainerCreating` state. This happens because the `external-proxy`
Pod cannot be started until the certificate has been acquired, and the
TLS made available as a secret to be mounted in the `external-proxy`
pod. If you observe this behavior, `cert-manager` has detailed
[troubleshooting documentation](https://cert-manager.io/docs/troubleshooting/)
on its website.

### Adding TLS to {insert-service-here}

Ultimately your service is likely being proxied through some
`external-proxy` config file. The only thing required to enable TLS
termination for a new service is to add a block like

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

The tls certificate is configured in the infrastructure pulumi chart
[here](/cluster/helm/infrastructure/src/network.ts). If changes are required there, e.g. updating the DNS names covered it, you will need to propagate a new certificate in all clusters. To do that manually in a running cluster, follow these steps:

1. `kubectl edit certificate -n cluster-ingress cn-<cluster>-certificate`
1. Edit the dnsNames list, and exit the editor
1. `kubectl delete secret -n cluster-ingress cn-<cluster>net-tls`
1. Wait for the secret to reappear in `kubectl get secret -n cluster-ingress`
1. Restart the external proxy, e.g. with `kubectl delete pod -n cluster-ingress external-proxy-<...>`

Note that these manual changes update an existing cluster, but you should make sure to update the pulumi chart consistently
for those changes to also persist for future cluster deployments.

## Participant Admin User Configuration

The participant admin user needs to be allocated during participant initialization.
Its desired user name is specified through the `CANTON_PARTICIPANT_ADMIN_USER_NAME` environment variable.
When using the `cn-participant` helm chart,
you can supply an environment variable source for obtaining the admin user name from a k8s secret,
via the `participantAdminUserNameFrom` value on the helm chart.

Here is how the admin user name of a typical SV participant is configured.
Using this user, the SV app later takes care of allocating the SV party, the SV validator user and,
if the SV is the SVC founder, also the SVC party.

```yaml
participantAdminUserNameFrom:
  secretKeyRef:
    key: ledger-api-user
    name: cn-app-sv-ledger-api-auth
    optional: false
```

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
cncluster update_secrets
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

## Testing the SV Helm Runbook

The [sv-runbook](./cluster/pulumi/sv-runbook) pulumi script reproduces the steps of the
[SV runbook for Helm deployment](./cluster/images/docs/src/sv_operator/sv_helm.rst).
It can be used to mimick a customer SV deployment, and can use the charts and images
from the artifactory (as published for versions deployed to DevNet&TestNet), or those built locally.

To build the required artifacts from your current local repo:

1. Run `make docker-push -j` to push docker images to GCP. You need to rerun this everytime you modify any of the images.
   Note that this does an incremental build. If things break, you can force a full rebuild by first running `make clean`.
1. Run `make cluster/helm/build` to build the Helm charts. You will need to rerun this every time you modify the helm charts.

The Pulumi script depends on the following env variables to be defined (e.g. by exporting them from your .envrc.private):

- AUTH0_DOMAIN: please use our sv-test domain: canton-network-sv-test.us.auth0.com
- AUTH0_CLIENT_ID: management client id of the sv-test domain, as obtained from https://manage.auth0.com/dashboard/us/canton-network-sv-test/apis/644fdcbfd1cecaff1c09e136/test
- AUTH0_CLIENT_SECRET: management secret of the sv-test domain, as obtained from https://manage.auth0.com/dashboard/us/canton-network-sv-test/apis/644fdcbfd1cecaff1c09e136/test
- ARTIFACTORY_USER: your username at digitalasset.jfrog.io (can be seen in the top-right corner after logging in with Google SSO)
- ARTIFACTORY_PASSWORD: Your identity token at digitalasset.jfrog.io (can be obtained by generating an identity token in your user profile)


To deploy the SV node following the runbook, cd to the scratchnet directory you wish to use, lock it, and run:

`cncluster papply_sv <cluster running the global domain> [<artifactory charts version>]`

By default, Pulumi will be using the charts and images as built locally and pushed to the dev artifactory using the `make` commands above.
It also supports deploying a version based on externally released artifacts, the ones customers use
by specifying their version in the `<artifactory charts version>` argument.

Once everything is up and running, you should be able to e.g. browse to the SV wallet at `https://wallet.sv.svc.sv.network.canton.global`.

To bring the deployment down, run:

`cncluster pdown_sv`


## Testing the SV Helm Runbook against a local build manually

If you wish to follow the SV runbook for Helm deployment manually, rather than through the Pulumi step, and use locally built resources:

When working against a local cluster, you can skip all the artifactory
setup in the runbook. We will instead be using docker images from
gcloud and local helm charts.

1. Lock the scratchnet cluster you want to use for testing using `cncluster lock`. Reset it if it's not empty already.
2. Create a file called `scratch.yaml` with the following content:

   ```
   imageRepo: "us-central1-docker.pkg.dev/da-cn-images/cn-images"
   cluster:
     fixedTokens: true
   ```

   This will configure Helm to fetch docker images from GCP and enable
   the fixed token mode which is used for all scratchnet clusters.
3. Run `make docker-push -j` to push docker images to GCP.
   Note that this does an incremental build. If things break, you can force a full rebuild by first running `make clean`.
4. Run `make cluster/helm/build` to build the Helm charts. You will need to rerun this every time you modify the helm charts.
5. Following the runbook, create `participant-values.yaml`,
   `validator-values.yaml` and `sv-values.yaml`. Replace
   `TARGET_CLUSTER` with the cluster you are testing against. Note
   that this is not the cluster you locked before, that will only
   contain your own SV node. You need a cluster that contains
   everything else. This cluster needs to run a compatible
   version. Often you can use staging or devnet for this. If that does
   not work, you may need to lock another scratchnet and go through
   the usual `cncluster apply` flow.  Use the following SV name, private and
   public key in `sv-values.yaml`:
   ```
   # SV identity for manual tests
   name = "DA-Test-Node"
   public-key = "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE7uz+zW1YcPJIl+TKqXv6/dfxcx+3ISVFgP6m2saeQ0l6r2lNW+WLfq+HUMcycxX9t6bUJ5kyEebYyfk9JW18KA=="
   private-key = "MIGHAgEAMBMGByqGSM49AgEGCCqGSM49AwEHBG0wawIBAQQgdRTS3iLr8rPFaLUBbVcu8qYxklmMzQo/4UXcULYESm2hRANCAATu7P7NbVhw8kiX5Mqpe/r91/FzH7chJUWA/qbaxp5DSXqvaU1b5Yt+r4dQxzJzFf23ptQnmTIR5tjJ+T0lbXwo"
   ```
   Use either of the user ids from
   [our list of passwords](https://docs.google.com/document/d/1ajR8_SsSybl6GSrhGggOHEZPfCF0hzk0MDJMyziV7Vc/edit?ouid=103930368588823687273&usp=docs_home&ths=true)
   as the `validator_wallet_user` in `validator-values.yaml` Use
   `https://canton.network.global` as the audience and
   `https://canton-network-dev.us.auth0.com/.well-known/jwks.json` as
   the JWKS url in `validator-values.yaml`.
6. Once you created the config files, you can run the `helm install` commands from the runbook. Instead of specifying the artifactory repo, specify the path
   to the local tarball created by `make cluster/helm/build`, by adding `-f scratch.yaml` to overwrite all docker images repo and omit `--version`.
   So `helm install participant canton-network-helm/cn-participant -n svc --version ${CHART_VERSION} -f participant-values.yaml`
   becomes `helm install participant $REPO_ROOT/cluster/helm/target/cn-participant-*.tgz -n svc -f participant-values.yaml -f scratch.yaml`.
7. If you made a change, uninstall the chart first, e.g., `helm
   uninstall participant -n svc --wait`.
   * Note the `--wait` which is useful if you intend to reinstall the chart, to make sure things are cleaned up before recreated. This is especially important for the ingress chart.
   * Note that uninstall postgres does
   not delete the persistent volume so in that case run `kubectl
   delete pvc -A --all`.
8. For the ingress, create `ingress-values.yaml` with the content from the runbook and then extend it with
   ```
   cluster:
    # existing cluster section stays here
    basename: YOUR_SCRATCHNET # e.g.. scratchb
    ipAddress: "34.171.136.234" # ip address of the cluster as output by `cncluster ipaddr`
   ```
   cert-manager will already be setup so no need to do anything about that.
9. You can reach the ingress at the usual address of you scratchnet, e.g., `https://scratchb.network.canton.global` or `https://sv.sv-1.svc.scratchb.network.canton.global`.

## SV Operations

Supervalidator nodes (SVs) play a central role in the governance and operation of each CN deployment.
Currently, each of our cluster deployments contains four SV nodes that are under our control (SV1 to SV4).
This means that we occasionally have to perform operations that SV operators need to perform.
The SV runbook is currently the main documentation for operating an SV node.
This section covers aspects not (yet) covered by the SV runbook as well has hints for managing our SV nodes specifically,
including hints on how to effectively interact with all four of them at the same time.

### Approving new SVs

To enable an external partner to onboard an SV node, we need to "approve" its SV identity on our SVs.
An SV identity consists of a name (string; must match whatever the candidate SV puts in its onboarding config)
and a public key (base64 string; must match the public and private key that the candidate SV puts in its onboarding config).
External partners need to tell us their name and public key before we can approve them.
The SV runbook prompts them to do so.

There are two ways of approving a new SV identity.
It's recommended to complete *both* ways - approval via API for instant approval, and approval via config for persisting the approval across redeploys.

#### Approving via SV API

Approval via the API is instant but not persisted across cluster redeploys.
Steps (from a cluster directory, e.g., `cluter/deployment/testnet`):

1. Run

```
$REPO_ROOT/scripts/approve-sv.sh $SV_NAME $SV_PUBLIC_KEY
```

2. Repeat the steps for every other cluster you want to update. Usually you should update at least devnet and testnet.

#### Approving via SV config

Approval via the configs of our SV apps requires a restart of those SVs,
but the approval is persisted across cluster redeploys.
To approve a new SV identity on all SVs, it is sufficient to add the new identity to the relevant `..ApprovedSvIdentities` lists in `cluster/pulumi/canton-network/src/installCluster.ts`:

```
const devNetApprovedSvIdentities = [ // or other list
  ...
  { name = "SV name", public-key = "SV key" },
  ...
]
```

It might be a good idea to deploy your changes to a scratchnet to ensure that you didn't break the config,
which would prevent our SVs from initializing after the next redeploy.

## Interacting with Canton Network UIs

To login to the following UIs use our test credentials from [our list of passwords](https://docs.google.com/document/d/1ajR8_SsSybl6GSrhGggOHEZPfCF0hzk0MDJMyziV7Vc/edit?ouid=103930368588823687273&usp=docs_home&ths=true):

| Endpoints                                                  | Description                                                                                            |
|------------------------------------------------------------|--------------------------------------------------------------------------------------------------------|
| `https://sv.sv-1.svc.<CLUSTER>.network.canton.global/`     | Admin user interface for Sv Operator to find information about the collective and perform admin tasks. |
| `https://wallet.sv-1.svc.<CLUSTER>.network.canton.global/` | User interface for the validators to transfer money and manage applications.                           |


## Configuring a New GCP Project

Rarely, there may be a need to configure a new GCP project for Canton
Network. Steps to do this are as follows:


1. Request that a project be created by sending a mail to
   `help@digitalasset.com`. The project should be derived from an
   existing CN project, and given the organization "'no
   organization'." Rights to this project should also be granted to
   `team-canton-network@digitalasset.com`.
2. Within the new project, create a Google Cloud Storage bucket named
   `da-cn-pulumi-${PROJECT_BASE}-stacks`, to be used as the Pulumi
   back end for the clusters in the project.
3. Create a `deployment` directory for a cluster in the new
   project. This directory can be populated with `.envrc` and
   `.envrc.vars` from another deployment directory, but environment
   specific configuration should be updated in `.envrc.var`. A few
   variables to consider are these:
      * `CLOUDSDK_CORE_PROJECT` - This should be the name of the newly
        created GCP project.
      * `PULUMI_BACKEND_URL` - This should be the `gs` URI for the
        Pulumi backend bucket created above.
4. Change into the new deployment directory, and run `cncluster
   activate` to authenticate to the project.
5. Enable the required GCE services with the following command: `gcloud
   services enable container.googleapis.com`.
6. Start creating a new cluster with `cncluster create`. Once this
   command starts working, you'll see in the GCE web UI that a new
   default service account has been created. It'll have a principal of
   the following form: '816347582626-compute@developer.gserviceaccount.com'.
7. Add a role binding to enable the new default service account to
   have access to `da-cn-images. The command to do this will look like
   this:

   ```
   gcloud projects add-iam-policy-binding da-cn-images \
      --member='serviceAccount:816347582626-compute@developer.gserviceaccount.com' \
      --role='roles/artifactregistry.serviceAgent'
   ```
8. Ensure the CCI Service account to be used for the project has the correct
   IAM role bindings:

   ```
   for ii in roles/compute.viewer roles/container.serviceAgent roles/logging.privateLogViewer roles/storage.objectAdmin roles/viewer
   do
     gcloud projects add-iam-policy-binding da-cn-scratchnet2 \
        --member='serviceAccount:circleci@da-cn-scratchnet.iam.gserviceaccount.com' \
        --role="${ii}"
   done
   ```

## Cluster Data Dumps

At the time of writing, only TestNet style deployments (i.e., deployment triggered with `export NON_DEVNET=1`) produce
data dumps. The setup for this works as follows.

All validator apps in a TestNet style deployment get provisioned with a key for
the service account `da-cn-data-exports@da-cn-devnet.iam.gserviceaccount.com` in the `da-cn-devnet` project. They use
that key to regularly produce dumps of their participant identities in the `da-cn-data-dumps` bucket in the
`da-cn-devnet` project. The files are stored using the following naming scheme:
```
<deployment>/<namespace>/participant_identities_<time>.json
```
The timestamps are written so that lexicographic sorting agrees with sorting by timestamp.

For example,
```
da-cn-data-dumps/test-preview/sv-1/participant_identities_2023-07-13T01:26:08.288478Z.json
```
is the file generated by the validator running
in the `sv-1` namespace of the `test-preview` deployment. You can download that file using this
[link](https://storage.cloud.google.com/da-cn-data-dumps/test-preview/sv-1participant_identities_2023-07-05T20%3A29%3A20.986308Z.json)
and browse to it
[here](https://console.cloud.google.com/storage/browser/_details/da-cn-data-dumps/test-preview/sv-1/participant_identities_2023-07-13T01:26:08.288478Z.json;tab=live_object?project=da-cn-devnet)

Furthermore, in a TestNet style deployment our founding SV node (SV-1) is also configured to regularly produce dumps of the contents of its ACS store
for the `svc` party. These dumps are stored using the same service account and target location as the participant identities dumps. For
example, one of the companion ACS dumps for the above participant identities dumps is
```
da-cn-data-dumps/test-preview/sv-1/svc_acs_dump_2023-07-13T02:55:07.211333Z.json
```
Note that only founding nodes support generating ACS dumps.

### Test and CircleCI setup

There are two kinds of tests that produce data dumps:
1. There are integration tests that verify the successful writing and reading of dumps from GCP.
   You can find them by searching for integration test classes with `Gcp` in their name.
2. There are preflight tests that check that dumps are being produced.
   You can find them by searching for integration test classes with `NonDevNet` in their name.

Both of these tests get their service account credentials from environment variables set in CircleCI. At the time of
writing, the setup is as follows.

Integration tests:
- use the credentials in `GCP_DATA_EXPORT_INTEGRATION_TEST_SERVICE_ACCOUNT_CREDENTIALS`,
  which contain a key for `da-cn-data-export-tests@da-cn-scratchnet.iam.gserviceaccount.com`
- read and write to the `da-cn-scratch-acs-store-dumps` bucket in the `da-cn-scratchnet` project

Preflight tests:
- use the credentials in `GCP_DATA_DUMP_BUCKET_SERVICE_ACCOUNT_CREDENTIALS`,
  which contain a key for `da-cn-data-exports@da-cn-devnet.iam.gserviceaccount.com`
- read from the `da-cn-data-dumps` bucket in the `da-cn-devnet` project

Note that you don't need to set any special environment variables when running these tests locally,
as they will automatically use your local Google Cloud SDK credentials setup through direnv.

### Pruning Data Dumps

Both of the buckets used for data dumps are setup to prune data automatically:
- after 7 days for `da-cn-scratch-acs-store-dumps`
- after 30 days for `da-cn-data-dumps`

### Bootstrapping from a Cluster Data Dump

To bootstrap a fresh cluster from an ACS and participant identities data dump,
set ``process.env.BOOTSTRAPPING_CONFIG`` to a JSON object specifying the cluster and the date the backup is from.

```
export BOOTSTRAPPING_CONFIG='{"cluster": "test-preview", "date": "2023-07-05T12:00:00.000Z"}'
```

The most recent backup before the specified date will be used to bootstrap from.
We only search for backups within an interval of 2 hours though,
so if no backup exists on [Google Cloud Storage](https://console.cloud.google.com/storage/browser/da-cn-data-dumps)
that is timestamped (as per its file name) at at most 2 hours before the specified date, your deployment attempt will fail.

When [deploying via CI](#manually-deploying-via-ci), you can use the `bootstrapping-config` parameter (in addition to `run-job`) to set the bootstrapping config (same format as above).

## Appendix: Kubernetes and Other Deployment Resources

* Kubernetes Documentation
   * [Fundamentals of Kubernetes](https://kubernetes.io/docs/concepts/overview/).
   * [`kubectl` Documentation](https://kubernetes.io/docs/reference/kubectl/)
   * [Liveness and Readiness Probes](https://kubernetes.io/docs/tasks/configure-pod-container/configure-liveness-readiness-startup-probes/)
   * [`Deployment`](https://kubernetes.io/docs/concepts/workloads/controllers/deployment/)
   * [`Service`](https://kubernetes.io/docs/concepts/services-networking/service/)
* [Object model API reference](https://kubernetes.io/docs/reference/generated/kubernetes-api/v1.22)
* [Google on Cloud Readiness](https://cloud.google.com/blog/products/devops-sre/want-repeatable-scale-adopt-infrastructure-as-code-on-gcp)
* Helm Chart Documentation
  * [Helm Charts](https://helm.sh/docs/topics/charts/)
  * [Go Templates](https://pkg.go.dev/text/template) (This is the templating engine used to generate Helm chart output)
* Pulumi Documentaiton
  * [Conceptual Overview](https://www.pulumi.com/docs/concepts/)
  * [Google Cloud Objects](https://www.pulumi.com/registry/packages/gcp/api-docs/)
  * [Kubernetes Objects](https://www.pulumi.com/registry/packages/kubernetes/api-docs/)
