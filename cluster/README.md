# Table of Contents

1. [Available Clusters](#available-clusters)
1. [Connecting to a Cluster](#connecting-to-a-cluster)
    1. [Connecting Locally Hosted Canton Network Apps to a Cluster](#connecting-locally-hosted-canton-network-apps-to-a-cluster)
    1. [Connecting Locally Hosted Canton Components to a Cluster](#connecting-locally-hosted-canton-components-to-a-cluster)
    1. [Network Configuration Within Kubernetes](#network-configuration-within-kubernetes)
1. [Granting VPN Access to External Partners](#granting-vpn-access-to-external-partners)
1. [Cluster Tooling](#cluster-tooling)
    1. [Manual Google Cloud Configuration](#manual-google-cloud-configuration)
    1. [Docker image hosting](#docker-image-hosting)
    1. [Cluster Management Operations](#cluster management-operations)
    1. [Recovery from a Failed CI/CD Deployment](#recovery-from-a-failed-ci/cd-deployment)
    1. [Checking Pod Node Assignments and Memory Usage](#checking-pod-node-assignments-and-memory-usage)
1. [Updating the Canton Network Deployment](#updating-the-canton-network-deployment)
1. [Fixing connection issues in kubectl](#fixing-connection-issues-in-kubectl)
1. [TLS Certificate Provisioning](#tls-certificate-provisioning)
   1. [First-time Infra Setup](#first-time-infra-setup)
   1. [Cluster Configuration](#cluster-configuration)
   1. [Adding TLS to {insert-service-here}](#adding-tls-to-insert-service-here)

Note that operations in this directory require authentication to use
Google Cloud APIs. If you have `direnv` installed (which you should),
you will be asked to authenticate when you change into this directory
for the first time.

## Available Clusters

The public Canton Network clusters are currently hosted in Google
Cloud. They are expected to be moved to Azure sometime in 1Q23.

There are multiple clusters, each with a different purpose, all of
which are accessible only through VPN:

| Cluster     | URL                                   | Deployment Policy                | Purpose                            |
|-------------|---------------------------------------|----------------------------------|------------------------------------|
| TestNet     | http://test.network.canton.global     | Weekly, Midnight UTC Sunday      | Longer Running Tests               |
| DevNet      | http://dev.network.canton.global      | Nightly, 6AM UTC                 | Current, Tested `main`             |
| Staging     | http://staging.network.canton.global  | After every push to `main`       | Latest `main`                      |
| ScratchNet  | http://scratch.network.canton.global  | Ad hoc, manual                   | Cluster Configuration Development  |

The automatic deployments are configured as
[Scheduled](https://app.circleci.com/settings/project/github/DACH-NY/the-real-canton-coin/triggers?return-to=https%3A%2F%2Fapp.circleci.com%2Fpipelines%2Fgithub%2FDACH-NY%2Fthe-real-canton-coin)
[CI/CD](/.circleci/config.yml) in CircleCI.

The ScratchNet cluster is manually managed and intended to be a test
bed for new code and deployment process updates. It is a shared
resource, so please coordinate with the team prior to making changes.

Additional clusters can be created without difficulty, although with
additional running costs.

## Connecting to a Cluster

The GCE clusters currently expose all of their services to the
outside world exclusively via four [Digital Asset VPNs](https://digitalasset.atlassian.net/wiki/spaces/DEVSECOPS/pages/1076822828/VPN+IP+Whitelist+for+Digital+Asset):

* Internal Digital Asset (Cluster Services and Kubernetes Management API's)
   * GCP Virginia Full Tunnel
   * GCP Frankfurt Full Tunnel
   * GCP Sydney Full Tunnel
* For External Users and Circle CI Prefligt Testing (Cluster Services Only)
   * GCP DA Canton DevNet
* For consultants working on CN
   * AWS DA Consultant VPN

Evven though the Kubernetes management API is accessible to all users
connecting through one of the internal VPN's, there are Google IAM
restrictions on those API's that grant access only to appropriate
users. CircleCI has its access to the Kubernetes Management API's (for
continuous deployemnt) through a specific grant to those servers' IP
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
  request goes to Edward Nnewman's team via a
  [manual e-mail request to IT](mailto:help@digitalasset.com) to add the
  account and send documentation to the external user.
* Once the account has been confirmed, the ticket can be closed.

### Available Cluster Services

Provided you are connecting through one of the listed VPNs, a full
list of services provided through the cluster is available via
`cncluster services` (or `cncluster stats`).

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
domain nodes.  If you don't have Canton, you may install it following
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

As part of the runbook a participant node is also spun up and connects
to the DevNet domain. Therefore, the runbook contains alternative
scripts for connecting a local participant to the DevNet domain.

### Network Configuration Within Kubernetes

The network configuration we use in our clusters is designed to
satisfy several key requirements:

* Access to our clusters needs to be initially protected from general
  access by the use of a VPN. (As we increase the robustness of our
  software stack, we will remove the requirment for the VPN.)
* The difference in cluster configuration between VPN and non-VPN
  operation should be kept as minimal as possible.
* We must support easy access to our gRPC API's from web clients.

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
is defined in a mangest generated by
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

* The service account must have access to the Artifact Registry within `da-cn-images`.
* The service account must have Read-Only access to the `release-bundles` Google Storage bucket.

### Docker image hosting

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
contains the connection configuration specific to that cluster. Operations
against that cluster must be invoked from within that directory. This
reduces the possibility of operating on the wrong cluster, and allows
the use of the `.envrc` mechanism to provide whatever configuration
is necessary to identify a given cluster.

Available operations include:


* `cncluster apply` - Apply the cluster configuration to the currently
  running CN cluster in GCE. (Accepts an optional argument that allows
  a specific set of images to be deployed by tag. Defaults to the current
  working directory's tag. Manifest is always deployed from working copy.)
* `cncluster check` - Run a series of simple validity checks against the
  external API exposed by a cluster.
* `cncluster create` - Create a new instance of the CN cluster in GCE,
  if it does not already exist.
* `cncluster delete` - Delete the currently running CN cluster from GCE.
* `cncluster deploy` - Build a set of images, push them, and deploy to the cluster.
* `cncluster ipaddr` - Return the toplevel IP address of the cluster.
* `cncluster preflight` - Run the preflight check against the cluster.
* `cncluster reset` - Delete all `Pod`s, forcing all memory state to
  be reset.
* `cncluster stats` - Show memory and CPU usage across the cluster.
* `cncluster wait` - Wait for the cluster's pods to all be noted as in a ready state.

Internally, these operations rely on the following environment
variables. As stated above, these are usually populated via `.envrc`.

| Variable Name             | Meaning                                                               |
| ------------------        | --------------------------------------------------------------------- |
| `CLOUDSDK_COMPUTE_REGION` | Google Cloud Region in which resources will be created                |
| `CLOUDSDK_CORE_PROJECT`   | ID of the Google Cloud project in which the cluster is located.       |
| `GCP_CLUSTER_BASENAME`        | Base of the cluster within the cloud project.  Used to compute the cluster's full name and DNS name.                   |
| `GCP_REPO_NAME`           | Google Cloud Project/Name of the image repository used to manage project container images. |

### Recovery from a Failed CI/CD Deployment

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

`(cd cluster/deployment/devnet && CI=true cncluster apply)`

Successful pod deplomyment can then be checked:

`(cd cluster/deployment/devnet && kubectl get pods)`

This should produce a list of pods, all in running status:

```
 $ kubectl get pods
NAME                                        READY   STATUS             RESTARTS      AGE
canton-domain-c476c45c4-mqnf8               1/1     Running            0             81s
canton-participant-84c9d9f56c-9mphh         1/1     Running            0             77s
directory-app-5f65cf4d6c-kzm74              1/2     Running            1 (31s ago)   56s
docs-856579dc5-rr6c6                        1/1     Running            0             96s
external-proxy-856cf65d66-48nlr             1/1     Running            0             52s
gcs-proxy-79646cf548-zrmfh                  1/1     Running            0             57s
scan-app-765877dfcb-ttskv                   1/1     Running            1 (64s ago)   88s
svc-app-589c858dc8-wcv84                    1/1     Running            3 (42s ago)   93s
validator1-participant-6659977887-xkz6z     1/1     Running            0             72s
validator1-validator-app-67bd778d4b-z4b4l   1/1     Running            2 (25s ago)   70s
validator1-wallet-app-7f7cfc6dbf-2tkzh      2/2     Running            1 (13s ago)   32s
validator1-wallet-web-ui-7777944f47-qz4vv   1/1     Running            0             62s
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

To skip the image pull backoff timeout, you can delete the failed pod,
which will force an immediate recreation of the pod and attempt to
repull the image.

```kubectl delete pod ${POD_NAME}```

**Every** pod can be deleted and reset as follows. This can be useful
for the moment, given that we rebuild the cluster nightly anyway.

```
cncluster reset
```

### Cluster Operational Dashboards

Each of our two GCE projects has a corresponding dashboard that shows
high level stats for the clusters hosted in that project. This
includes information on memory, network, and CPU usage trends over
time:

* [`TestNet`/`DevNet`](https://console.cloud.google.com/monitoring/dashboards/builder/f4d4f86d-7c59-4b27-9a73-fb6e0418e45b?project=da-cn-devnet&dashboardBuilderState=%257B%2522editModeEnabled%2522:false%257D&timeDomain=1m)
* [`Staging`/`ScratchNet`](https://console.cloud.google.com/monitoring/dashboards/builder/ef100871-4e71-409e-a3c2-706b2dbd5465?project=da-cn-scratchnet&dashboardBuilderState=%257B%2522editModeEnabled%2522:false%257D&timeDomain=1m)

### Checking Pod Node Assignments and Memory Usage

Kubernetes runs Docker images in `Pods` that are scheduled to run on
`Node`s based on the requirements of the pod and the available
capacity within the nodes available to the cluster. We currently run
without cluster autoscaling enabled, which means we have a fixed pool
of nodes. In this environment, it is possible for nodes to be in an
`Evicted`, `OOMKilled`, or `Pending` state due to memory
limitations. None of these states are normal and any of them indicate
a problem that likely needs to be corrected.

A list of nodes available to the cluster can be requested from
`kubectl` as follows:

```
$ kubectl get nodes
NAME                                           STATUS   ROLES    AGE     VERSION
gke-cn-stagingnet-default-pool-285c4578-34nx   Ready    <none>   7h8m    v1.22.12-gke.300
gke-cn-stagingnet-default-pool-285c4578-5qkw   Ready    <none>   7d23h   v1.22.12-gke.300
gke-cn-stagingnet-default-pool-285c4578-929v   Ready    <none>   7h8m    v1.22.12-gke.300
gke-cn-stagingnet-default-pool-285c4578-gwen   Ready    <none>   7d23h   v1.22.12-gke.300
gke-cn-stagingnet-default-pool-285c4578-kfvr   Ready    <none>   30m     v1.22.12-gke.300
gke-cn-stagingnet-default-pool-285c4578-mzs1   Ready    <none>   7d23h   v1.22.12-gke.300
gke-cn-stagingnet-default-pool-285c4578-scv2   Ready    <none>   30m     v1.22.12-gke.300
```

The corresponding command to list pods may also be run in a wide
format, which shows the `Node`s on which a given `Pod` is scheduled:

```
$ kubectl get pods -o wide
NAME                                        READY   STATUS    RESTARTS      AGE   IP            NODE                                           NOMINATED NODE   READINESS GATES
canton-domain-69c9dcfbf6-pr8w2              1/1     Running   0             42m   10.92.1.211   gke-cn-stagingnet-default-pool-285c4578-5qkw   <none>           <none>
canton-participant-7f4dd65887-x5l2z         1/1     Running   0             42m   10.92.2.75    gke-cn-stagingnet-default-pool-285c4578-mzs1   <none>           <none>
directory-app-6c4d49868c-4pp8b              2/2     Running   0             42m   10.92.6.2     gke-cn-stagingnet-default-pool-285c4578-scv2   <none>           <none>
docs-845c84dc5-t6zdj                        1/1     Running   0             42m   10.92.6.3     gke-cn-stagingnet-default-pool-285c4578-scv2   <none>           <none>
external-proxy-55d954c97b-k7gln             1/1     Running   0             42m   10.92.2.76    gke-cn-stagingnet-default-pool-285c4578-mzs1   <none>           <none>
gcs-proxy-84bd947f54-tpc24                  1/1     Running   0             42m   10.92.4.37    gke-cn-stagingnet-default-pool-285c4578-34nx   <none>           <none>
scan-app-6bb5f9668f-q4b6b                   1/1     Running   1 (42m ago)   42m   10.92.4.36    gke-cn-stagingnet-default-pool-285c4578-34nx   <none>           <none>
svc-app-78b84cf7-jhpg9                      1/1     Running   3 (42m ago)   42m   10.92.0.43    gke-cn-stagingnet-default-pool-285c4578-929v   <none>           <none>
validator1-participant-7d6ff497ff-2hk4f     1/1     Running   0             42m   10.92.3.152   gke-cn-stagingnet-default-pool-285c4578-gwen   <none>           <none>
validator1-validator-app-58667ffcc7-mn98t   1/1     Running   3 (41m ago)   42m   10.92.1.212   gke-cn-stagingnet-default-pool-285c4578-5qkw   <none>           <none>
validator1-wallet-app-d7ffcf8fc-qvnxc       2/2     Running   3 (41m ago)   42m   10.92.0.44    gke-cn-stagingnet-default-pool-285c4578-929v   <none>           <none>
```

To get a summary view of cluster status, you can use `cncluster
stats`, which will show a display of cluster status at both a pod and
a node level.

To further investigate `Pod`s in an invalid state, additional details
may be requested through `kubectl describe pod ${POD_NAME}`. This will
show, among many other details, a log of recent events related to the
`Pod`'s deployment within Kubernetes. You can also describe `Node`s to
get details on capacities with `kubectl describe node ${NODE_NAME}`.


## Updating the Canton Network Deployment

This section provides simple step-by-step instructions for how to change a CN cluster.
For details on the individual steps, read the above sections.

1. Acquire at least a minimal knowledge about [kubernetes](https://kubernetes.io/docs/concepts/overview/). TLDR:
   1. A _container_ is a portable executable image that contains software and all of its dependencies.
   2. A _pod_ is a group of containers with shared storage/network resources, running in a shared environment.
   It's analogous to a set of applications running on the same machine.
   Containers within a pod can reach each other's ports on `localhost`.
   A pod also defines what ports it exposes to the outside world.
   3. A _service_ is a logical set of pods and a policy by which to access them.
   A service defines what ports it exposes, and where requests sent to those ports are routed to (e.g., to pods).
   4. Each service gets a DNS name equal to `<service-name>.<namespace-name>`,
   pods in the same namespace can use simply `<service-name>`.
   Use service DNS names for communication between pods.
2. Edit the canton network cluster definition
    1. If you need to add a new asset (e.g., a new frontend bundle):
       make sure that asset is included in the bundle produced by `sbt bundle`.
       That release bundle is included in the `./cluster/images/cn-app` image,
       which is used by many of our other images.
    2. If you need to edit an existing cluster component
       (e.g., change the config of some app backend):
       edit the corresponding folder in `./cluster/images`.
       Each component defines its own docker image.
    3. If you need to add a new component:
        1. Add a new folder to `./cluster/images`
           1. Make sure the new folder contains a Dockerfile and a Makefile that depends on `common.mk`.
              When in doubt, start by duplicating an existing component.
        2. Edit `./Makefile`, adding the component at the end of the existing list of components
           1. Note that the order matters, if the image of your component depends on the image of another component,
              your component must be listed after the dependency.
        3. Edit `./cluster/manifest/canton-network-config.jsonnet`, adding the new component to the `cantonNetwork()` function
        4. If the new component has an API that should be reachable from the internet,
           add a new config file to `./cluster/images/external-proxy/conf`
    4. Note that you are responsible for making sure the ports defined in different config files are consistent.
       In particular, consider:
       1. `./cluster/manifest/canton-network-config.jsonnet` (ports used within the cluster)
       2. `./cluster/images/external-proxy/config` (egress of the cluster)
       3. config files baked into individual component images (ports that the applications actually use)
3. If you touched `./cluster/manifest/canton-network-config.jsonnet`,
   run `make -C cluster/manifest test-update`
4. Make sure you are connected to a full tunnel VPN
   whenever you run a command interacting with the cloud.
5. Build and upload all docker images
    1. The dependency tracking of the build system is currently not reliable. Do the following workarounds:
       1. Run `make clean`. This deletes all `./cluster/images/**/target` folders and forces the next
          `make` invocation to rerun `sbt bundle`. It does not do a full `sbt clean` though..
       2. If you still run into any issues, run `make clean && sbt clean` to trigger a full rebuild.
    2. Run `make`
    3. Run `make -C cluster docker-push`
    4. Do not edit any local files while running `make docker-push`.
6. Deploy your cluster definition to scratchnet
    1. Scratchnet is used for ad-hoc testing, and we only have one instance of scratchnet.
       Coordinate with team members if you are not sure that you are the only one using it.
    2. Run `cd cluster/deployment/scratchnet` and execute all following commands in this section from that directory
    3. Run `cncluster apply` to deploy your cluster definition.
        1. If you get errors about missing images, re-upload your docker images.
7. Debug your deployment on scratchnet
   1. Run `cd cluster/deployment/scratchnet` and execute all following commands in this section from that directory
   2. Run `kubectl get pods` to get the status of all pods.
   3. Run `kubectl describe pod <pod-name>` to get a detailed status of the given pod.
   4. Run `kubectl logs` to download application logs
      1. Run `kubectl logs -l app=<app-name>` to get the log for the given application.
      2. Run `kubectl logs -l 'app in (<app-name>, <app-name>)'` to get a combined log for all the given applications.
      3. Run `kubectl logs <pod-name>` to get the application log for the given pod.
      4. Add `--tail=-1` to get the complete log snapshot (no limit on the number of lines returned)
      5. Add `-f` to get the live log (new entries streaming to your console)
      6. Add `--since=30m` to only return entries from the past 30min
      7. Add `-p` to get the log from the previous instance. Use this to access the log of a crashed container after it restarted.
   5. Run `kubectl get svc` to get an overview of ports used within the cluster.
   6. Use `lnav` to quickly analyze log files downloaded using `kubectl logs`.
      Before opening the log file in `lnav`, manually remove the first ~100 lines that use a different log line format, otherwise `lnav` will not work correctly.
   7. If you prefer a web UI to read logs, open `https://console.cloud.google.com/logs/query?project=da-cn-scratchnet`
8. Test CN applications on scratchnet
   1. Run `CLUSTER_ADDR=scratch.network.canton.global coin -v -c ./build-tools/cluster.conf` to connect a CN console
      to the scratchnet cluster. The config file should allow you to use any component deployed to the cluster.
      1. E.g., run `validator1_validator.onboardUser("dave")` inside the coin console to onboard a new end-user onto the validator1 node.

## Fixing connection issues in kubectl

If `kubectl get pods` times out for a given cluster and you know
you are connected to the VPN, it may be necessary to
force `.kubecfg` to be regenerated. This can be required 
if someone else has fully rebuilt the cluster. (`cncluster delete`/`cncluster create`)

To do so run the following commands from the cluster directory, e.g. `cluster/deployment/staging`:

```
rm .kubecfg
direnv reload
```

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

## Auth0 secrets

Our apps need some secrets in order to interact with auth0.
Our tooling expects that these secrets are stored in environment variables on your machine. It is recommended you store them in `.envrc.private`.
From there, the secrets are copied to kubernetes secrets and mapped to environment variables on the target pods.
These values of these environment variables are then inserted into app config files using standard HOCON environment variables substitution.

In order to update secrets on a deployed cluster, run `cncluster update_secrets`.
It will expect the following environment variables:

- `CN_APP_<APP>_LEDGER_API_AUTH_CLIENT_ID`: client ID of the given auth0 application on the [dev tenant](https://manage.auth0.com/dashboard/us/canton-network-dev).
- `CN_APP_<APP>_LEDGER_API_AUTH_CLIENT_SECRET`: client secret of the given auth0 application on the [dev tenant](https://manage.auth0.com/dashboard/us/canton-network-dev).
