# Table of Contents

- [Table of Contents](#table-of-contents)
  - [Available Clusters](#available-clusters)
  - [Auth0 Tenants/Applications](#auth0-tenantsapplications)
    - [`canton-network-dev` Tenant](#canton-network-dev-tenant)
    - [`canton-network-sv-test` Tenant](#canton-network-sv-test-tenant)
    - [`canton-network-validator-test` Tenant](#canton-network-validator-test-tenant)
  - [Connecting to a Cluster](#connecting-to-a-cluster)
    - [Granting VPN Access to External Partners](#granting-vpn-access-to-external-partners)
    - [Connecting Locally Hosted Canton Network Apps to a Cluster](#connecting-locally-hosted-canton-network-apps-to-a-cluster)
    - [Network Configuration Within Kubernetes](#network-configuration-within-kubernetes)
    - [Check live IP whitelist](#check-live-ip-whitelist)
    - [Check cluster ingress and egress IPs](#check-cluster-ingress-and-egress-ips)
  - [Cluster Tooling](#cluster-tooling)
  - [Node Pools](#node-pools)
  - [Cluster Deployments](#cluster-deployments)
    - [Operator deployments](#operator-deployments)
      - [Deployment stack configuration](#deployment-stack-configuration)
      - [Upgrading to a new release](#upgrading-to-a-new-release)
      - [The operator](#the-operator)
      - [Alerts](#alerts)
  - [Pulumi and Helm](#pulumi-and-helm)
    - [Manual Google Cloud Configuration](#manual-google-cloud-configuration)
    - [Docker Image Hosting](#docker-image-hosting)
    - [Cluster Management Operations](#cluster-management-operations)
    - [DevNet and TestNet](#devnet-and-testnet)
      - [Strategies for reacting to a failed TestNet or DevNet deployment](#strategies-for-reacting-to-a-failed-testnet-or-devnet-deployment)
      - [Testing deploy-devnet and deploy-testnet changes](#testing-deploy-devnet-and-deploy-testnet-changes)
    - [Manually Deploying via CI](#manually-deploying-via-ci)
      - [Confirming the Deployment](#confirming-the-deployment)
    - [CloudSQL and ScratchNet Clusters](#cloudsql-and-scratchnet-clusters)
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
      - [Debugging problems with GKE](#debugging-problems-with-gke)
      - [GCE Log-based Metrics](#gce-log-based-metrics)
      - [Prometheus Metrics and Grafana Dashboards](#prometheus-metrics-and-grafana-dashboards)
        - [Prometheus Metrics](#prometheus-metrics)
        - [Grafana Dashboards](#grafana-dashboards)
        - [The Observability Cluster](#the-observability-cluster)
      - [Alerts](#alerts)
        - [Grafana Alerts](#grafana-alerts)
          - [Creating an alert](#creating-an-alert)
          - [Updating an alert](#updating-an-alert)
      - [JVM debug information](#jvm-debug-information)
      - [Connecting to a Postgres database](#connecting-to-a-postgres-database)
    - [Checking Pod Node Assignments and Memory Usage](#checking-pod-node-assignments-and-memory-usage)
    - [Managing GKE Kubernetes Versions](#managing-gke-kubernetes-versions)
      - [Automatic GKE Cluster Upgrades](#automatic-gke-cluster-upgrades)
  - [Interacting with a Canton Network Cluster](#interacting-with-a-canton-network-cluster)
    - [Gaining Access to a Cluster](#gaining-access-to-a-cluster)
      - [Fixing Connection Issues in kubectl](#fixing-connection-issues-in-kubectl)
    - [Cluster Infrastructure Setup](#cluster-infrastructure-setup)
    - [Deploy a Build to a Cluster](#deploy-a-build-to-a-cluster)
      - [Deploy a previous CN version to a Cluster](#deploy-a-previous-cn-version-to-a-cluster)
    - [Add a Component to the Build](#add-a-component-to-the-build)
    - [Modifying a Deployed Cluster](#modifying-a-deployed-cluster)
    - [Manual Cleanup for an Interrupted Deployment](#manual-cleanup-for-an-interrupted-deployment)
    - [Memory Settings](#memory-settings)
  - [TLS Certificate Provisioning](#tls-certificate-provisioning)
    - [Adding TLS to {insert-service-here}](#adding-tls-to-insert-service-here)
    - [Force-updating the certificate](#force-updating-the-certificate)
  - [Participant Admin User Configuration](#participant-admin-user-configuration)
  - [Token configuration](#token-configuration)
  - [Testing the SV Helm Runbook](#testing-the-sv-helm-runbook)
  - [Scaling the Cluster Size for Testing](#scaling-the-cluster-size-for-testing)
    - [SV Scaling](#sv-scaling)
    - [Validator Scaling](#validator-scaling)
  - [SV Operations](#sv-operations)
    - [Approving new SVs](#approving-new-svs)
      - [Approving via SV API](#approving-via-sv-api)
      - [Approving via SV config](#approving-via-sv-config)
    - [Participating in a hard domain migration or in a disaster recovery](#participating-in-a-hard-domain-migration)
      - [Via the Pulumi operator](#via-the-pulumi-operator)
        - [Troubleshooting external stacks](#troubleshooting-external-stacks)
      - [Checking the readiness of partners](#checking-the-readiness-of-partners)
      - [New domain readiness checks](#new-domain-readiness-checks)
      - [Switching back to the old domain](#switching-back-to-the-old-domain)
  - [Interacting with Canton Network UIs](#interacting-with-canton-network-uis)
  - [Interacting with Canton Network APIs](#interacting-with-canton-network-apis)
    - [Canton Participant APIs](#canton-participant-apis)
    - [App APIs without authentication](#app-apis-without-authentication)
    - [App APIs with authentication](#app-apis-with-authentication)
  - [Configuring a New GCP Project](#configuring-a-new-gcp-project)
  - [Creating a New Cluster](#creating-a-new-cluster)
  - [Cluster Data Dumps](#cluster-data-dumps)
    - [Test and CircleCI setup](#test-and-circleci-setup)
    - [Pruning Data Dumps](#pruning-data-dumps)
    - [Bootstrapping from a Cluster Data Dump](#bootstrapping-from-a-cluster-data-dump)
  - [Testing](#testing)
    - [Writing Tests against different Clusters](#writing-tests-against-different-clusters)
    - [Patching healthchecks against a deployed cluster](#patching-healthchecks-against-a-deployed-cluster)
  - [Backup and Recovery](#backup-and-recovery)
    - [Backup](#backup)
    - [Restore](#restore)
  - [Chaos Mesh](#chaos-mesh)
  - [Maintenance Windows](#maintenance-windows)
  - [Multi-architecture Docker Images](#multi-architecture-docker-images)
  - [Docker-compose](#docker-compose)
  - [Testing Performance-Critical Changes](#testing-performance-critical-changes)
  - [Testing compatibility of Dev/Test/Mainnet topology with the next major Canton version](#testing-compatibility-of-devtestmainnet-topology-with-the-next-major-canton-version)
  - [Deploying with KMS](#deploying-with-kms)
  - [Appendix: Kubernetes and Other Deployment Resources](#appendix-kubernetes-and-other-deployment-resources)

Note that operations in this directory require authentication to use
Google Cloud APIs. If you have `direnv` installed (which you should),
you will be asked to authenticate when you change into this directory
for the first time.

## Available Clusters

The global Canton Network clusters are currently hosted in Google
Cloud. There are multiple clusters, each with a different purpose, all
of which are accessible only through VPN:

| Cluster         | URL                                                   | Deployment Policy           | Purpose                                |
|-----------------|-------------------------------------------------------|-----------------------------|----------------------------------------|
| TestNet         | http://test.global.canton.network.digitalasset.com    | Weekly, Midnight UTC Sunday | Longer Running Tests                   |
| DevNet          | http://dev.global.canton.network.digitalasset.com     | Weekly, 3AM UTC Monday      | Current, Tested `main`                 |
| CIDaily         | http://cidaily.global.canton.network.digitalasset.com | Nightly, 6AM UTC            | Current, Tested `main`                 |
| CIMain          | http://cimain.global.canton.network.digitalasset.com  | After every push to `main`  | Latest `main`                          |
| CILR            | http://cilr.global.canton.network.digitalasset.com    | Monthly, on the first       | Test behavior of long running clusters |
| ScratchNetA     | http://scratcha.network.canton.global                 | Ad hoc, manual              | Cluster Configuration Development      |
| ScratchNetB     | http://scratchb.network.canton.global                 | Ad hoc, manual              | Cluster Configuration Development      |
| ScratchNetC     | http://scratchc.network.canton.global                 | Ad hoc, manual              | Cluster Configuration Development      |
| ScratchNetD     | http://scratchd.network.canton.global                 | Ad hoc, manual              | Cluster Configuration Development      |
| ScratchNetE     | http://scratche.network.canton.global                 | Ad hoc, manual              | Cluster Configuration Development      |

The URLs are derived from `$GCP_CLUSTER_HOSTNAME` and `$GCP_CLUSTER_BASENAME`, defined in `cluster/deployment/${cluster}/.envrc.vars`.

The automatic deployments are configured as
[Scheduled](https://app.circleci.com/settings/project/github/DACH-NY/canton-network-node/triggers?return-to=https%3A%2F%2Fapp.circleci.com%2Fpipelines%2Fgithub%2FDACH-NY%2Fcanton-network-node)
[CI/CD](/.circleci/config.yml) in CircleCI.

The `ScratchNetX` clusters are manually managed and intended to be
test beds for new code and deployment process updates. These are a
shared resource, so please coordinate with the team prior to making
changes.

## Auth0 Tenants/Applications

Across all our Canton Network clusters, we use the same set of Auth0 applications to handle auth for various components of our three deployment stacks. A list of these applications along with a description of how they are used follows:

### `canton-network-dev` Tenant

This tenant is used by components of the `canton-network` Pulumi stack to deploy:
* four supervalidator nodes `SV1-4` along with their wallet, directory and SV UIs
* a Splitwell instance backed by its own validator
* a standalone validator `Validator1` along with wallet, CNS and splitwell UIs

(In the table below i goes from 1 to 4)

| Application Name             | Type                    | Purpose                                                      |
|------------------------------|-------------------------|--------------------------------------------------------------|
| API Explorer Application     | Machine to Machine      | Managing users for tests                                     |
| SVi backend                  | Machine to Machine      | Auth for SV1-4 backends                                      |
| SVi validator backend        | Machine to Machine      | Auth for SV1-4 validator backends                            |
| Splitwell backend            | Machine to Machine      | Auth for splitwell backend                                   |
| Splitwell validator backend  | Machine to Machine      | Auth for splitwell validator backend                         |
| Validator1 backend           | Machine to Machine      | Auth for Validator1 backend                                  |
| SVi Frontends                | Single Page Application | Auth for Wallet, CNS and SV UIs for SV1-4              |
| Splitwell UI                 | Single Page Application | Auth for Splitwell UI                                        |
| Validator1 UI                | Single Page Application | Auth for Wallet, CNS and Splitwell UIs for Validator1  |

### `canton-network-sv-test` Tenant

This tenant is used by components of the `sv-runbook` Pulumi stack to deploy:
* a supervalidator `SV` along with associated wallet, CNS and SV UIs

| Application Name             | Type                    | Purpose                                                      |
|------------------------------|-------------------------|--------------------------------------------------------------|
| API Explorer Application     | Machine to Machine      | Managing users for tests                                     |
| SV backend                   | Machine to Machine      | Auth for SV backend                                          |
| Validator backend            | Machine to Machine      | Auth for SV validator backend                                |
| Wallet UI                    | Single Page Application | Auth for SV Wallet UI                                        |
| CNS UI                 | Single Page Application | Auth for SV CNS UI                                     |
| SV UI                        | Single Page Application | Auth for SV UI                                               |

### `canton-network-validator-test` Tenant

This tenant is used by components of the `validator-runbook` Pulumi stack to deploy:
* a standalone validator `Validator` along with associated wallet and CNS UIs

| Application Name             | Type                    | Purpose                                                      |
|------------------------------|-------------------------|--------------------------------------------------------------|
| API Explorer Application     | Machine to Machine      | Managing users for tests                                     |
| Validator app backend        | Machine to Machine      | Auth for Validator backend                                   |
| Wallet UI                    | Single Page Application | Auth for Validator Wallet UI                                 |
| CNS UI                 | Single Page Application | Auth for Validator CNS UI                              |

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

### Connecting Locally Hosted Canton Network Apps to a Cluster

The preferred way to connect a locally hosted Canton Network App to a
cluster is documented in the externally facing documentation in the
section on Self Hosting apps. This is available through the cluster
specific documentation that we make available through the cluster
links. The source for this documentation is available
[here](/cluster/images/docs/src/validator_operator/self_hosting.rst).

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

### Check live IP whitelist

You can see the active whitelist of the cluster by running

```
kubectl describe svc -n cluster-ingress istio-ingress | grep "LoadBalancer"
```

### Check cluster ingress and egress IPs

You can get the cluster's egress IP by running:

```
cncluster pulumi infra stack output
```

## Cluster Tooling

This repository also contains tools for managing clusters hosted in
Google Cloud, running Google's GKE implementation of Kubernetes.  The
specific configuration for these clusters is defined in a combination of
[Pulumi scripts](/cluster/pulumi) and [Helm charts](/cluster/helm).

All cluster management commands are defined as subcommands of the
`cncluster` script, and are written in terms of Pulumi charts.

## Node Pools

Each cluster consists of three node pools:

* `gke-pool` for GKE pods
* `cn-infra-pool` for infrastructure for CN deployments, e.g. observability, istio and cert-manager
* `cn-apps-pool` for the CN deployment pods

The pools have `node-labels` and `taints` accordingly, and pods have `affinity` and `tolerances` that match them. If a pod fails to be scheduled, inspect its
affinity and tolerances configuration and make sure they match the node pool
on which it should run. For now, every cluster also has a `default-pool` which
should be a fallback default if no other pool can be used for a certain pod,
but this pool will be deprecated soon [TODO (#13026): update this once we
delete default-pools], so please refrain from using it.


## Cluster Deployments

### Operator deployments

The `pulumi/deployment` project controls the deployment of production clusters using the [pulumi operator](https://github.com/pulumi/pulumi-kubernetes-operator).

#### Deployment stack configuration

The pulumi operator uses a custom docker image to allow us to use a specific pulumi version and to configure the default pulumi arguments (like parallelism).
The operator is configured using a [flux source](https://fluxcd.io/flux/components/source/). A flux source allows a pulumi deployment to follow a certain git
reference (e.g. a branch), and automatically apply changes pushed to that reference.

The `operator` Pulumi project installs the operator, and deploys a `deployment`
stack, which will typically follow `main`, and deploy the other stacks per the
versions specified for them in the deployment directory in `main`.


These deployment watch the migration specific git reference configured under the key `releaseReference` and applies that git deployment code
to the cluster.
Each migration can follow a different release that is upgraded independent of the other migrations. The key `synchronizerMigration.active.releaseReference` controls the release used for all our main deployments and the infra stack.
The deployment uses `dotenv` to read the cluster specific env configuration files.
The version used for the deployment is set using `synchronizerMigration.active.version` in `config.yaml` or defaults to `CHARTS_VERSION`.

The infra stack and the canton network stack are included by default.
The other stacks can be included through the use of env variables:
- SPLICE_DEPLOY_VALIDATOR_RUNBOOK
- SPLICE_DEPLOY_MULTI_VALIDATOR
- SPLICE_DEPLOY_SV_RUNBOOK

#### Upgrading to a new release

To upgrade a cluster controlled by the operator, you need to go
through the following steps:

1. Run the `publish-public-artifacts` job on CI.
2. Make a PR where you:

   1. Set `CHARTS_VERSION` and `OVERRIDE_VERSION` in the `.envrc.vars` file of the corresponding cluster.
   2. If the active migration configured in the `cluster.yaml` under the key `synchronizerMigration.active.releaseReference`
      of the corresponding cluster is a release line branch (this should be true for DevNet/TestNet/MainNet, usually not for CILR),
      then update it to the release line of the new version.
   3. Update any circleci periodic triggers for the cluster that run on the release line branch to the new release.

   This PR should be made against `main` and against the release line of the new release.

3. If the active migration configured in the `cluster.yaml` under the key `synchronizerMigration.active.releaseReference` of
   the corresponding cluster is a Git tag, e.g., `cilr` for CILR, then tag the merged commit on `main` with that tag.


#### The operator

The operator is deployed in each cluster in the `operator` namespace.
Logs for the operator can be checked, for example for the `cilr` cluster and `canton-network` stack, with the following google query

```
resource.type="k8s_container"
resource.labels.cluster_name="cn-cilrnet"
resource.labels.namespace_name="operator"
resource.labels.container_name="pulumi-kubernetes-operator"
json_payload."Request.Name"="canton-network"
```


#### Alerts

The operator exposes a basic set of prometheus metrics, that we use to create alerts if a stack is in failing state for a set period of time.

There's also a dashboard that allow to easily view the current state/historical state.
The dashboard is available in grafana, for exampel for CILR: [Pulumi Operator Dashboard](https://grafana.cilr.network.canton.global/d/QP_wDqDnz/pulumi-operator-stacks-dashboard?orgId=1&from=now-1h&to=now&refresh=30s)

#### Stack files

We normally store our stack files (Pulumi.<project>.<stack>.yaml files) separately from
Pulumi sources, and refer from the stack file directories to the sources through a
`main: <path>` entry in a `Pulumi.yaml` file next to the stack files. This requires that
we run `npm install` from the Pulumi project directory and not the stacks directory, which
we do locally and in CI through `make cluster/pulumi/build`. Unfortunately, the operator
does not do that, and it runs the `npm install` command in the working directory, i.e.
where the stacks are, which then fails with `pulumi SDK does not seem to be installed`
messages.

Therefore, in operator deployments, we first copy the stack files into the Pulumi project
directory, using [Flux's `Include` feature](https://fluxcd.io/flux/components/source/gitrepositories/#include), see [flux-source.ts](../cluster/pulumi/common/src/operator/flux-source.ts#L63).


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

### Versions and Repositories

The version is set to `${next-version}-${user}-dirty` by default for images and helm charts that are built locally.

All images and Helm charts are pushed to the **Development** Github Container Registry at
ghcr.io/digital-asset/decentralized-canton-sync-dev/docker and ghcr.io/digital-asset/decentralized-canton-sync-dev/helm.
This includes artifacts pushed manually, snapshots created in CCI, and release versions.

The us-central1-docker.pkg.dev/da-cn-shared/ghcr Google Artifact Repository is setup as a `remote` repository to ghcr.io.
This is used by cluster deployments and caches the ghcr repositories.

Releases are copied to
ghcr.io/digital-asset/decentralized-canton-sync/docker and ghcr.io/digital-asset/decentralized-canton-sync/helm
when they are published using the `publish-public-artifacts` CCI workflow.

If you want to deploy a specific release or snapshot version instead of a locally built one,
you can set the `CHARTS_VERSION` and `OVERRIDE_VERSION` environment variables in the `.envrc.vars` file of the corresponding cluster.

### Deploy a local build to a cluster
1. Start from a clean slate: `make -C $SPLICE_ROOT clean`
1. Ensure docker images are built and pushed to the Docker repository: `make -C $SPLICE_ROOT docker-push -j`
   - Note that this step internally calls `sbt bundle` to build the most recent version of our apps.
     later steps don't call `sbt bundle` automatically, as it takes too long.
   - Note: helm charts built locally reference the docker images using just your username.
     Make sure to `make docker-push`, whenever you want to propagate local changes to the Development Artifactory Docker Registry.
   - If this fails, you may need to run `echo $GH_TOKEN | docker login "$GHCR" -u "$GH_USER" --password-stdin` to login to the Github Container Registry.
1. If you plan on using manual `pulumi` commands for deployment (i.e., calling `cncluster pulumi ...` instead of `cncluster apply`),
   ensure that pulumi is set up: `make -C $SPLICE_ROOT cluster/build -j`
   - This step is handled automatically by `cncluster apply`. This uses locally built helm charts by default.
1. Start with a working cluster and change to its deployment directory.
   - You need be authorized to the GCP project for the environment to be loaded successfully after `direnv allow .`.
1. Try to acquire the cluster lock `cncluster lock` and go to a different scratchnet if it's already locked.
   - If you want to lock the first available scratch cluster, you can do so with `deployment/lock-first-scratch.sh`
     but you'll have to have entered all the scratchnet directories and authorized the environment with `direnv allow`
     beforehand.
1. Delete the existing cluster resources managed by pulumi: `cncluster reset`.
   - This should typically not be required as the cluster is reset upon unlocking it.
   - You may need to run `gcloud auth application-default login` for Pulumi to pick up the correct GCP token.
1. Apply the Pulumi cluster configuration: `cncluster apply`.
   - To successfully apply the configuration, you need some Artifactory- and Auth0-related environment
     variables correctly exported by the `.envrc.private` file in the project root as described in
     [the main README](../README.md#private_environment_variables).
     In particular, the `ARTIFACTORY*`, `*CN_MANAGEMENT*`, `*SV_MANAGEMENT*` and `*VALIDATOR_MANAGEMENT*`
     environment variables are always required.
   - Use `kubectl get pods -A` to observe creation of the four new SV App nodes.
   - You can also use the graphical `k9s` tool for this purpose, see its [docs here](https://k9scli.io/).
   - Some tips for handling deployment failures:
     - *Secrets not containing the right values*: decode the secret using something like
       `kubectl get secret -n sv-1 cn-gcp-bucket-da-cn-devnet-da-cn-data-dumps -o 'jsonpath={.data.json-credentials}' | base64 -d`
       or use `k9s` to navigate to the secrets overview using `:secrets` and press `x` on the secret of interest.
     - *Cancelled pulumi holding the lock*: release the lock using `cncluster pulumi canton-network cancel`. `cncluster reset` will also cancel and retry the reset on detecting a held Pulumi lock. See also the section on [Manual Cleanup for an Interrupted Deployment](#manual_cleanup_for_an_interrupted_deployment).
     - Cluster deployment can be flaky for a variety of infrastructural reasons; these flakes can often be solved
       just by retrying `cncluster apply`.
     - See also the section on [Modifying a Deployed Cluster](#modifying-a-deployed-cluster)
1. The Pulumi and Helm charts may now be edited and `cncluster apply`
   once again used to apply only the changes to the cluster.
1. Release the cluster lock `cncluster unlock`, which also resets the cluster.



### Manual Google Cloud Configuration

Most of the task of setting up clusters is automatic and scripted, but
there are a few aspects that must be manually configured for each GCE
Project through the Google Cloud Console UI.  When a new project is
created within GCE that will host a Canton Network cluster, the
following grants must be made within `da-cn-shared` to the default
compute service account within the new cluster.

* The service account must have Read-Only access to the `cn-release-bundles` Google Storage bucket.

### Docker Image Hosting

Docker images for both local and GCE clusters are stored in the
[development Github container registry](ghcr.io/digital-asset/decentralized-canton-sync-dev/docker), public releases are mirrored at
[public releases Github Container Registry](ghcr.io/digital-asset/decentralized-canton-sync/docker). These registries do not require credentials.

Amongst others, the following environment
variables are defined in [`.envrc.vars`](.envrc.vars):

| Variable Name             | Meaning                                                               |
| ------------------        | --------------------------------------------------------------------- |
| `CLOUDSDK_COMPUTE_REGION` | Google Cloud Region in which resources will be created                |
| `CLOUDSDK_CORE_PROJECT`   | ID of the Google Cloud project in which the cluster is located.       |

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

* `cncluster apply` - Apply the current working copy's
  `canton-network`, `infra`, `validator1` and `splitwell` stacks to a cluster,
  as well as all `sv-canton` stacks required by the SVs that will be deployed out of `canton-network`.
  Useful flags include `--skip-infra` (to skip the infra stack)
  and `--sv1-only` (to deploy only SV1 and no other SVs or validators;
  **warning** this will tear down existing deployments that are not associated to `sv-1`!).
  For big deployments it can be beneficial to run `cncluster apply --sv1-only` first,
  followed by another round of `cncluster apply` to deploy everything else.
  The presence of all images referenced by that
  configuration is confirmed prior to application of the manifest.

  * The tag for the images to be deployed can be overridden with an
    optional parameter. If this is specified, then the docker image
    presence check is also bypassed.
  * To docker image check can also be bypassed by setting the
    `CNCLUSTER_SKIP_DOCKER_CHECK` environment variable to 1. This
    can also be added to `.envrc.private`.
  * One can skip redeploying the `infra` stack by supplying the `--skip-infra` flag.

* `cncluster apply_sv` - Apply the sv-runbook Pulumi stack.
      * You need to provide a `target-domain-cluster` argument, for instance `scratcha` for scratchneta.
* `cncluster apply_operator` - Apply the deployment Pulumi stack.
      * To deploy it on a scratchnet as of release `X.X.X` (for a **stable** release), you need to follow these steps:
          * cd into your scratchnet's deployment directory
          * `export OPERATOR_IMAGE_VERSION=X.X.X`
          * `export GOOGLE_CREDENTIALS=$(cat "$HOME/.config/gcloud/application_default_credentials.json")`
          * `git checkout -b <some_temp_branch>`
          * `cncluster update_config active 0 <X.X.X> refs/heads/<some_temp_branch> canton-network-node`
          * `cncluster set_operator_deployment_reference refs/heads/<some_temp_branch> canton-network-node`
          * push `config.yaml` to the temporary branch
          * `cncluster apply_operator`
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
  If canton network is not installed yet on the cluster, run `cncluster apply` first.
* `cncluster preflight_sv` - Run the SV preflight check against the cluster.
  If the sv-runbook is not installed yet on the cluster, run `cncluster apply_sv` first.
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

`cncluster` also has basic autocompletion for bash. To install that, add a line: `source <this script>` to your ~/.bashrc

### DevNet, TestNet and MainNet

DevNet releases are cut manually as described in [RELEASE.MD](../RELEASE.MD) when needed and then deployed.
TestNet should always upgrade to a release already on DevNet with the exception of urgent bugfixes.
MainNet should always upgrade to a release already on TestNet with the exception of urgent bugfixes.

#### Confirming the Deployment

To confirm the deployment, you can use a command like the following to
inspect pod state. The `--all-namespaces` flag is necessary because we
run our clusters with a Kubernetes namespace for each
sub-module. Without this flag, `kubectl get pods` will skip listing
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

### CloudSQL and ScratchNet Clusters


By default, scratchnet clusters do not enable CloudSQL and instead deploy
self-hosted postgres instances. This is mainly done to speed up
deployment.

If you explicitly want to test a CloudSQL deployment on scratchnet,
add the following config to the `config.yaml` file of the cluster:

```
pulumiProjectConfig.canton-network.cloudSql.enabled: true
```

This will enable CloudSQL for all the pulumi projects.

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

Each of our GCE projects has a corresponding dashboard that shows
high level stats for the clusters hosted in that project. This
includes information on memory, network, and CPU usage trends over
time:

* [`TestNet`/`DevNet`](https://console.cloud.google.com/monitoring/dashboards/builder/f4d4f86d-7c59-4b27-9a73-fb6e0418e45b?project=da-cn-devnet&dashboardBuilderState=%257B%2522editModeEnabled%2522:false%257D&timeDomain=1m)
* [`Staging`/`ScratchNet`](https://console.cloud.google.com/monitoring/dashboards/builder/ef100871-4e71-409e-a3c2-706b2dbd5465?project=da-cn-scratchnet&dashboardBuilderState=%257B%2522editModeEnabled%2522:false%257D&timeDomain=1m)
* [`cilr`](https://console.cloud.google.com/monitoring/dashboards/builder/80e9d615-0230-4566-a391-1264215d1fd4;duration=P1M?project=da-cn-ci)

#### GCE Log Explorer

Google Cloud offers central log aggregation through its Log Explorer
feature, available here:

* [`TestNet`/`DevNet`](https://console.cloud.google.com/logs/query?project=da-cn-devnet)
* [`Staging`/`ScratchNet`](https://console.cloud.google.com/logs/query?project=da-cn-scratchnet)
* [CI Clusters (`daily`, `daily-testnet`, `cilr`, and `main`)](https://console.cloud.google.com/logs/query;duration=P1M?project=da-cn-ci)

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

#### Debugging problems with GKE

In order to facilitate debugging cluster deployment problems that point to issues with GKE, enabling additional logging is an option.
These issues often manifest in Pulumi deployments as:

```text
client rate limiter Wait returned an error: context deadline exceeded
```
or
```text
the server is currently unable to handle the request
```

There are 2 [additional logging options](https://cloud.google.com/kubernetes-engine/docs/concepts/about-logs) available in GKE - control plane logs and audit logs.

###### Kubernetes control plane logs

These are logs for k8s control plane components eg. the Kubernetes API server, scheduler and controller manager and are configured on a per-cluster basis.
To enable these, go to Kubernetes > Clusters, select your GKE project from the drop-down, click on the cluster name and scroll down to Features.
Click on the Edit icon next to Logging and select the components for which to enable logs. It may take a while after you click "Save Changes" for your
changes to be registered with GKE.

###### Audit logs

By default, our GKE clusters already have Admin Activity audit logging enabled. This includes logs that write metadata or configuration information eg.
creating, modifying or deleting k8s resources (basically any GKE API method that starts with Create, Update, Set, or Delete). To also enable audit logging
for read API requests (Get, Watch, List), you need to explicitly enable Data Access audit logs. To do so, go to IAM & Admin > Audit Logs, search for
Kubernetes Engine API and add a tick mark against the log types you want to enable (Admin Read, Data Read and Data Write). For a description of what each log type
represents, see [Enable Data Access audit logs](https://cloud.google.com/logging/docs/audit/configure-data-access)

##### Useful log filters

- `resource.type="k8s_control_plane_component"` - provides all control plane logs
- `protoPayload.authenticationInfo.principalEmail=~"circleci@.*"` - provides audit logs for the circleci service account

#### GCE Log-based Metrics

Google Cloud has a feature to create [log-based metrics](https://cloud.google.com/logging/docs/logs-based-metrics) from the logs it aggregates.
We currently have such a metric configured for the number of unexpected warnings and errors we see in our GKE clusters for the `da-cn-ci2` project
[here](https://console.cloud.google.com/logs/metrics?project=da-cn-ci-2). GCE also allows configuring alerts based on these metrics by setting up
alerting policies in the [Google Alerting dashboard](https://console.cloud.google.com/monitoring/alerting/policies?project=da-cn-ci-2).

At the time of writing, we have such an alert policy configured for our `cilr` cluster that sends notifications to the `#temp-canton-network-internal-log-alerts` Slack channel.
When investigating an incident that caused this alert to fire, you might want to see the offending logs. Clicking on the incident link
contained within the alert will take you to the incident details page in the Google Cloud console where you should find a "View Logs" button.
See [0.n9uo152f10jk](https://console.cloud.google.com/monitoring/alerting/incidents/0.n9uo152f10jk?channelType=slack&amp;project=da-cn-ci-2&project=da-cn-ci-2&pageState=(%22interval%22:(%22d%22:%22P1D%22,%22i%22:(%22s%22:%222024-02-28T06:09:40.000Z%22,%22e%22:%222024-02-28T07:09:44.000Z%22))
for an example incident details page. Since we filter for the cluster and namespace in the alerting policy and not the log filter directly, you will want to add the following log filter
to what you get from "View Logs" to identify the exact set of logs that caused the alert to fire:

```
resource.labels.cluster_name="cn-cilrnet"
-resource.labels.namespace_name="sv-4"
```

Note that at present we filter out the `sv-4` namespace to reduce noise because our crash fault tolerance tests constantly take down containers in this namespace.

#### Prometheus Metrics and Grafana Dashboards

##### Prometheus Metrics

We collect metrics from various components in the system into Prometheus, which is deployed in every cluster. At the moment, in addition to basic k8s metrics, metrics are also collected from:
- Participants (the entry point to the ledger for each validator/user)
- Sequencers (the component in the Canton domain responsible for ensuring a total order between all messages on that domain)
- Mediators (the component in the Canton domain responsible for collecting and verifying confirmations from participants)
- CometBFT (the replication service currently used to create a BFT sequencing service)
- PostgreSQL
- All our apps

Prometheus may be configured to collect more metrics by deploying `ServiceMonitor` resources in k8s. We typically do that as part of the Helm chart of the corresponding component. To access and see the raw collected data in Prometheus, browse to `prometheus.<CLUSTER_DNS>`

##### Grafana Dashboards

Grafana is used to visualize Prometheus metrics in various ways, aggregated into Dashboards. We deploy two types of dashboards in all clusters:

- Custom dashboards built by us. To add a new dashboard, add its JSON definition to a file in `cluster/pulumi/infra/grafana-dashboards`. It will then be pushed to the cluster via the `infra` Pulumi stack, under the `canton-network` folder in Grafana.
  If you have defined a new dashboard, you can add a new `.json` file there by copy pasting from Dashboard Settings > JSON Model.
- Dashboards from the grafana public marketplace, installed using the dashboard id. Check `observability.ts` for example of such dashboards
- Default k8s dashboards that are deployed by the prometheus/grafana helm chart

To access Grafana on a given cluster, browse to `grafana.<CLUSTER_DNS>` and login with the credentials in [our shared passwords document](https://docs.google.com/document/d/1ajR8_SsSybl6GSrhGggOHEZPfCF0hzk0MDJMyziV7Vc/edit?ouid=103930368588823687273&usp=docs_home&ths=true).

Dashboards that we extended from other sources:
- [canton-network/deployment.json](https://github.com/pulumi/pulumi-kubernetes-operator/blob/master/docs/metrics.md) The pulumi operator dashboard, heavily extended compared to the sample dashboard found in the README
- [canton-network/cometbft.json](https://grafana.com/grafana/dashboards/11036-cosmos-blockchains/) Heavily extended version of the cosmos blockchains dashboard
- [database/postgres_overview.json](https://grafana.com/grafana/dashboards/455-postgres-overview/) Modified version
- [database/postgres_exporter.json](https://grafana.com/grafana/dashboards/12485-postgresql-exporter/) Modified version
- [k8s/overview.json](https://grafana.com/grafana/dashboards/13332-kube-state-metrics-v2/) Modified version

##### The Observability Cluster

In addition to the per-cluster observability stacks, we maintain also an `observability` cluster which collects metrics data from CircleCI runs. You can access Grafana on that cluster at `https://grafana.observability.network.canton.global`. The observability cluster is a regular CN cluster but it only deploys the `infra` stack.

Note that contrary to our other clusters, the `observability` cluster
exposes the prometheus remote write endpoint to the public internet
(but protected through JWT auth). This avoids the need to run
literally every job on CI through VPN.

In the observability cluster, we add an ad hoc filter using the `build_num` label with
a default value of `non_existing`.
The main reason is to prevent loading a lot of metric series when first opening the dashboard (as all the metrics are
labeled for each CI run job with a different `build_num`, this would load a lot of series in prometheus leading to
really high memory usage), and allowing us to filter
for a specific `build_num` much easier.

This filter will in turn have the side effect of not having any data when first opening a dashboard, but the user would
have to select an existing build num and adjust the time period to match the time of the CI run for the data to be
fetched.


#### Alerts

We have configured alert policies on Google Cloud for our `devnet`, `testnet`, and `testnet-preview` clusters.
Our policies can be configured and extended via the
[Google alerting dashoard](https://console.cloud.google.com/monitoring/alerting?project=da-cn-devnet).
When an alert triggers, we are notified over Slack on the `#team-canton-network-internal-ci` channel.
At the time of writing, we have only configured alerts on unexpectedly high CPU usage and
disk volume utilization beyond 80%.

##### Grafana Alerts

We configure the alerting system for grafana in the `cluster/pulumi/infra/grafana-alerting` folder. To create a new alert,
head to the Alert Rules page in Grafana (`https://grafana.<CLUSTER_NAME>.network.canton.global/alerting/list`) and add
a new alert rule either from scratch or by copying an existing one. You can then click on "View YAML" to export its YAML
configuration, add it in the `grafana-alerting` folder and update `observability.ts` to ensure it gets automatically picked up
and provisioned during cluster deployment.

**Note**

The Grafana alerting UI will also show the existing alerts that are configured in prometheus itself.
It will also show the state of the alert (firing/pending).
These alerts will not trigger notifications through the grafana alert manager.
This is not something that grafana will support (https://github.com/grafana/grafana/issues/73447).
For those alerts to actually trigger a notification we will have to configure the prometheus alert manager.

**NoData alerts**

During our cluster resets, all the alerts would trigger with state no data.
The No Data alerts would trigger immediately and ignore the pending period as set.
Tracking issue in grafana: https://github.com/grafana/grafana/issues/16290

Because of the issue above we have disabled alerts when the state is NoData. This should be reverted if the grafana
issue is fixed.


###### Creating an alert

Creating a new alert can be done through the UI.
- Follow all the steps and configure the alert as you wish
- Save the new alert
- View the alert after saving it
- Click `View Yaml`
- Copy the yaml in a new/existing provisioning file in the `grafana-alerting` folder

If you want to test your alert on a separate channel before merging you can set the following variables before deploying a scratchnet:
```
export SLACK_ACCESS_TOKEN="Ask a team member for the token"
export SLACK_ALERT_NOTIFICATION_CHANNEL_FULL_NAME="tmp-canton-network-deployments"
export SLACK_ALERT_NOTIFICATION_CHANNEL="C0702L4N161"
export ENABLE_ALERTS_FOR_TESTING=true
```

###### Updating an alert

To edit an alert follow the following steps:
- Go to the alert and click `Modify export`
- Edit the alert as you wish
- Once done, click export and copy the resulting yaml

##### Cluster-based Alerts

###### CometBFT maximum block rate

This alert triggers when the block rate of CometBFT exceeds a certain threshold. The alert is configured to trigger
when the block rate exceeds **expectedMaxBlocksPerSecond** blocks per second.

This variable is optional and can be configured per cluster in the config.yaml file:

#### JVM debug information

To collect low-level debug information for a JVM process running in the cluster, follow these steps:

1. Install a JMX client application. VisualVM and JDK Mission Control are both free and tested to work.
1. Use `kubectl get pods -A` to find the name of the pod on which the JVM application is running.
1. Use `kubectl port-forward pods/<pod-name> 9010:9010 --namespace=<namespace>` to forward port
   9010 from the pod to your local network.
1. In your JMX client application, open a connection to `localhost:9010`, without SSL or authentication.
1. In your JMX client application, start a Java Flight Recorder with the desired settings and analyze the result.

#### Connecting to a Postgres database

For a cluster using CloudSQL, the database is exposed only through a private IP, accessible through a dedicated VPN.
It is by default configured to allow connections from the corresponding cluster, so by running a debug container in the cluster,
one can connect to it for debug purposes. To do that, run `cncluster debug_shell` in the cluster deployment directory.
This should get you a terminal in an Ubuntu pod running on the cluster, in which `psql` client is installed.

You can then run, e.g. `psql -h <hostname> -U cnadmin -l` to list all databases in the Postgres server.
The hostname can be found by describing the relevant pods that use the database you wish to connect to,
(e.g. using `kubectl describe pod -n sv-1 sv-app`).
The password can be found in the `postgres-secrets` secret of the namespace:
(e.g. using `kubectl get secret postgres-secrets -n sv-1 -o jsonpath='{.data.postgresPassword}' | base64 -d`).

If `cncluster debug_shell` fails, use `kubectl describe pod -n default splice-debug` to find out why.
A common problem is that the `splice-debug` image is missing for your local version.

#### Inspecting daml transactions

Daml transactions are too large to be logged in full, so they are truncated in the cluster logs.
To view historical transaction data, you can inspect the UpdateHistory database of the scan or validator application:
the scan application stores all transactions visible to the DSO party, the validator application stores all transactions visible to end-user parties.

To inspect the update history, connect to the postgres database of a validator or scan application as described in the previous section.
In the `psql` shell, run:

- `\dn` to list all schemas, and note the schema used by the application.
- `select row_id from <schema>.update_history_transactions where update_id = '<update_id>'`
  to get the row_id of a transaction with a given update ID.
  Note that you might get multiple results if the transaction was visible to multiple parties.
- `select * from <schema>.update_history_exercises where update_row_id = <update_row_id>`
  with the row_id from the previous query to get the details of all exercise nodes in the transaction.

For more complex queries, inspect the database schema in the Flyway migration scripts in `apps/common/src/main/resources/db/migration`.

There is no easy way to pretty-print contract arguments, choice arguments, or exercise results
the same way they used to be pretty-printed in the logs.
The postgres function `jsonb_pretty` can be used to pretty-print generic JSONB columns,
but the result is very verbose and does not contain daml record field names.

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
can be managed with the `cncluster infra_*` commands or with `cncluster pulumi infra`.
The following commands cover the typical lifecycle of a Canton Network cluster.

* `cncluster infra_up` / `cncluster pulumi infra up` - Apply the infrastructure
  configuration to the cluster.
* `cncluster infra_down` / `cncluster pulumi infra down` - Remove the configured
  infrastructure for the cluster.
* `cncluster pulumi infra refresh` - Refresh Pulumi's infrastructure
  state database based on the current cluster infrastructure. This
  is useful when a cluster configuration is updated externally.

### Deploy a Build to a Cluster

1. The scratchnet clusters are used for ad-hoc testing, and we have a limited
   number of instances, shared across the team. To claim a cluster for your use,
   run `cncluster lock`  from the cluster's deployment directory, which will
   then assert a lock on the cluster in your name (unless somebody else has
   it already).
1. Deploy your cluster. See [Pulumi and Helm](#pulumi-and-helm).
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

#### Deploy a previous CN version to a Cluster

1. Checkout the branch with the version you want to deploy to get the right helm charts,
1. Overwrite the `get-snapshot-version` file with the version (e.g. `echo <version>`),
1. Run `make cluster/build` from the repository root to build the helm charts with the correct version,
1. Run `cncluster apply` in your target cluster.

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
For example, you might want to:
- *whitelist a new ip*: `kubectl edit svc -n cluster-ingress istio-ingress` (modify the `loadBalancerSourceRanges` section)

Alternatively, for changes in a Helm chart, or in the values with which it is deployed, also consider one of the following options:

To uninstall a single Helm chart, and have Pulumi reinstall it with whatever modifications you made locally:
1. Run `helm list -A` to see a list of all deployed Helm chart in all namespaces, and find the one of interest
1. Uninstall the current Helm chart using `helm uninstall -n <namespace> <name>`, for example `helm uninstall -n sv-1 sv-1-sv-app
1. Run `cncluster prefresh` to get Pulumi to refresh its state against that of the cluster
1. Run `cncluster apply` to get Pulumi to reinstall the uninstalled chart.

Alternatively, you can also modify an installed chart, e.g. to change the values with which it is installed, or simply to reinstall it to apply local changes to the chart:
1. Run `helm list -A` to see a list of all deployed Helm chart in all namespaces, and find the one of interest
1. Run `helm get values -n <namespace> <name> > vals.yaml` to get the values with which the chart is currently installed
1. Edit `vals.yaml`: delete the first line ("USER-SUPPLIED VALUES:"), and modify whatever values you wish to change
1. Run `helm upgrade -n <namespace> <name> $SPLICE_ROOT/cluster/helm/target/<your-helm-chart>.tgz -f vals.yaml`

### Manual Cleanup for an Interrupted Deployment

After an interrupted `cncluster apply`, running `cncluster reset` may not be enough to clean up, and subsequent `apply`s may fail with errors like:

```
  kubernetes:core/v1:Namespace (splitwell):
    error: resource splitwell was not successfully created by the Kubernetes API server : namespaces "splitwell" already exists

  kubernetes:core/v1:Namespace (docs):
    error: resource docs was not successfully created by the Kubernetes API server : namespaces "docs" already exists

  kubernetes:core/v1:Namespace (sv-1):
    error: resource sv-1 was not successfully created by the Kubernetes API server : namespaces "sv-1" already exists

  ...
```

This can be remedied by deleting the offending namespaces and rerunning `apply`:

```
kubectl delete namespace sv-1 sv-2 sv-3 sv-4 validator1 splitwell docs
```

### Allowed IP Ranges

The allowed IP ranges are stored in `allowed-ip-ranges.json` for the respective network for any external IP and
[`allowed-ip-ranges-cn-internal.json`](./allowed-ip-ranges-cn-internal.json) for the IPs of our own clusters.

Note that the external allowed IP ranges are managed in the
[configs-private](https://github.com/global-synchronizer-foundation/configs-private)
repo so any change should be made there and then after merging it, bump the
submodule in this repo.

To update the submodule to a new commit, use the following command. But please note that
we don't necessarily need to accept any change pushed to the config repos. Please review
all changes that you consume before applying this command.

```
git submodule update --remote
```

On a daily basis, PRs are created for the production release line branches that bump this submodule.
But, as mentioned above, please review all changes before approving and merging that PR.
To trigger a bump earlier than the daily automation, you may also trigger the `bump-configs` workflow
in CircleCI (on the main branch), which will create that bump PR.

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

Note that these manual changes update an existing cluster, but you should make sure to update the pulumi chart consistently
for those changes to also persist for future cluster deployments.

### Using Let's Encrypt staging issuer

Let's Encrypt, our auth certificate provider, has a rate limit of 50 certificates per
registerd domain on its production server. Repeatedly bringing down and up the pulumi
infra stack can easily exhaust that, and block us for days. When developing in the
infra stack, if you do need to bring that back and up for testing, please
`export USE_LETSENCRYPT_STAGING=true` in order to use Let's Encrypt staging server
instead. The certificates will not be fully signed, thus raise exceptions and browser
alerts, but otherwise the flow is the same, so should suffice for developing the infra
stack itself.

## Participant Admin User Configuration

The participant admin user needs to be allocated during participant initialization.
Its desired user name is specified through the `CANTON_PARTICIPANT_ADMIN_USER_NAME` environment variable.
When using the `splice-participant` helm chart,
you can supply an environment variable source for obtaining the admin user name from a k8s secret,
via the `participantAdminUserNameFrom` value on the helm chart.

Here is how the admin user name of a typical SV participant is configured.
Using this user, the SV app later takes care of allocating the SV party, the SV validator user and,
if the SV is the DSO founder, also the DSO party.

```yaml
participantAdminUserNameFrom:
  secretKeyRef:
    key: ledger-api-user
    name: splice-app-sv-ledger-api-auth
    optional: false
```

## Token configuration

By default, our apps are configured using client ids and secrets and
use a client credentials flow to request tokens from Auth0 on
startup. However, auth0 has limits on how many tokens can be requested
per month. To work around this, we configure our non-production clusters
with fixed static tokens.

The behavior can be switched by setting the following environment variable.

```
export CNCLUSTER_FIXED_TOKENS=1
```

## Testing the SV Helm Runbook

The [sv-runbook](./cluster/pulumi/sv-runbook) pulumi script reproduces the steps of the
[SV runbook for Helm deployment](./cluster/images/docs/src/sv_operator/sv_helm.rst).
It can be used to mimick a customer SV deployment, and can use the charts and images
from Artifactory (as published for versions deployed to DevNet & TestNet), or those built locally.

To build the required artifacts from your current local repo:

1. Run `make docker-push -j` to push docker images to GCP. You need to rerun this everytime you modify any of the images.
   Note that this does an incremental build. If things break, you can force a full rebuild by first running `make clean`.
1. Run `make cluster/helm/build` to build the Helm charts. You will need to rerun this every time you modify the helm charts.

The Pulumi script depends on the following env variables to be defined
(e.g. by exporting them from your `.envrc.private`):

- `AUTH0_SV_MANAGEMENT_API_CLIENT_ID`: management client id of the sv-test domain, as obtained from https://manage.auth0.com/dashboard/us/canton-network-sv-test/apis/644fdcbfd1cecaff1c09e136/test
- `AUTH0_SV_MANAGEMENT_API_CLIENT_SECRET`: management secret of the sv-test domain, as obtained from https://manage.auth0.com/dashboard/us/canton-network-sv-test/apis/644fdcbfd1cecaff1c09e136/test
- `ARTIFACTORY_USER`: your username at digitalasset.jfrog.io (can be seen in the top-right corner after logging in with Google SSO)
- `ARTIFACTORY_PASSWORD`: Your identity token at digitalasset.jfrog.io (can be obtained by generating an identity token in your user profile)


To deploy the SV node following the runbook, cd to the scratchnet directory you wish to use, lock the cluster with `cncluster lock`, and run:

`cncluster apply_sv <cluster running the global domain> [<artifactory charts version>]`

By default, Pulumi will be using the charts and images as built locally and pushed to the dev artifactory using the `make` commands above.
It also supports deploying a version based on externally released artifacts, the ones customers use
by specifying their version in the `<artifactory charts version>` argument.

Once everything is up and running, you should be able to e.g. browse to the SV wallet at `https://wallet.sv.<CLUSTER_BASENAME>.network.canton.global`, where CLUSTER_BASENAME depends on the scratchnet you deployed to (`scratcha` for `scratchneta` and so on)

To bring the deployment down, run:

`cncluster pdown_sv`

## Scaling the Cluster Size for Testing

### SV Scaling

When deploying a cluster, it is possible to enable additional SVs beyond the typical 1-4, for the purpose of
creating and testing larger networks. Currently a max value of `9` is supported.

To enable these additional SVs, set `DSO_SIZE=<num>` for that cluster's env configuration, then `cncluster apply`.

Note that the keys for the extra SVs need to be present in the Google Cloud project that the cluster is running in. Currently the keys are only uploaded to `da-cn-scratchnet`.

### Validator Scaling

Validators are scaled out via the `multi-validator` Pulumi stack. To add more test validators to the network, set the cluster env
variable `MULTIVALIDATOR_SIZE=<num>`. As 1 multivalidator contains 10 validator nodes, this results in `10 * <num>` available validators.

Because they are deployed from a separate stack, run `cncluster apply_multi` after `cncluster apply`.

When `MULTIVALIDATOR_SIZE > 0`, the load-tester (if enabled) automatically begins using _all_ test validators for p2p transfers.
A value of `0` tells the load-tester to not use any of the test validators and default back to using `validator1` only.

## SV Operations

Supervalidator nodes (SVs) play a central role in the governance and operation of each CN deployment.
Each of our cluster deployments contains multiple SV nodes that are under our direct control (e.g., SVs 1-4, the runbook SV).
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

#### Approving via SV config

SV identities should be changed in the [configs](https://github.com/global-synchronizer-foundation/configs) repo.
After merging a PR there, bump the submodule in this repo.

2. if you cannot wait the next update then, checkout the deployment branch of the cluster you want to update. You can run `make cluster/helm/build` to rebuild the helm charts, and then `cncluster apply` to redeploy the SVs.

As this process is quite subtle, ask in #team-canton-network-internal to pair with someone that has done this before. You can also deploy your changes to a scratchnet to ensure that you didn't break the config,
which would prevent our SVs from initializing after the next redeploy.

### Participating in a hard domain migration or in a disaster recovery

Our end of the "Synchronizer Upgrades with Downtime" flow.
Note that this also includes some steps for our non-SV validators.
The following steps assume that:

- The cluster is already deployed and is at migration ID 0.
- We already know that a migration will take place.

See also: [Operating on Production Clusters](../OPERATIONS.md)

### Spreadsheet steps

As part of HDMs and DR practices there's a Google Docs spreadsheet where each SV partner marks themselves as ready for each step.
We're also expected to follow the steps in the spreadsheet, which map to the instructions on the next section.
However, the following steps don't require an action from us:

- "There is identities dump from your SV available": this dump is taken when the application starts.
  If you really want to make sure, you can check the logs of the SV app for an entry that says "Wrote node identities dump" [GCloud Logs example](https://console.cloud.google.com/logs/query;query=resource.labels.cluster_name%3D%22cn-testnet%22%0A%22Wrote%20node%20identities%20dump%22%0A;summaryFields=resource%252Flabels%252Fnamespace_name:false:32:beginning;cursorTimestamp=2024-10-11T05:19:14.578112132Z;duration=PT30M?project=da-cn-devnet) around app startup.
- "Copy Dump to sv-app": this is done as part of the `cncluster copy_disaster_recovery_dumps` command.
- "Restored from the dump": this is done automatically by our pulumi/helm setup, but you do need to validate everything works properly afterward.

#### Via the Pulumi operator

1. Start a Slack thread on which you document the steps you take.
   Make sure there is at least one CN engineer around for a second pair of eyes.
1. **Prepare:** Merge a PR against the target deployment branch (s.a.: [operator deployments](#operator-deployments)) that sets the following config for your target cluster:
   * set in `config.yaml` under the path `synchronizerMigration.upgrade.version` the version we're migrating to (current one in case of disaster recovery)
   * set in `config.yaml` under the path `synchronizerMigration.upgrade.id` the migration ID we're migrating to
   * set in `config.yaml` under the path `synchronizerMigration.upgrade.provider` the type of canton deployment used. `internal` for all the canton components in the `canton-network` or `sv-runbook` stack, `external` to create canton specific stacks for each sv for the migration
   * if using `external` as the provider, set in `config.yaml` under the path `synchronizerMigration.upgrade.releaseReference` the git tag or branch that will be used for the deployment code
   * environment variable `export DISABLE_COMETBFT_STATE_SYNC="true"` for a slightly faster migrate step
   * if using `external` as the provider, generate and commit the Pulumi secret provider configuration files for the
     new stacks using the steps outlined in [troubleshooting external stacks](#troubleshooting-external-stacks) to
     ensure the pulumi preview job on your PR succeeds.
1. Once the operator has applied your changes successfully and you can confirm that the cluster is (still) healthy (no alerts, health check failures etc.), report to our partners that you have completed the prepare step (setting a good example).
1. Make sure that a sufficient number (ideally all) of our partners have also prepared their SVs for migration.
   See [below](#checking-the-readiness-of-partners) for ideas on how to determine this.
1. Only for Hard Domain Migrations: Coordinate with all other SVs to schedule a migration via governance vote.
   For testing (or if our SVs have a governance majority) you can also run `cncluster hard_domain_migration_trigger`.
1. Make sure that [validators](https://daholdings.slack.com/archives/C06QB1ZEGCE) are informed about the scheduled hard migration, the expected downtime associated with that, and the expected version and migration ID after the migration.
1. Deactivate our periodic health checks for the target cluster by merging a PR to `main` (close to the scheduled synchronizer pausing date).
   If periodic SV runbook redeployments are scheduled for the target cluster, deactivate those as well.
   You can optionally disable backups and backup status checks. If you don't, they will just fail with no further consequences,
   at least let people on monitoring rotation know that this is expected until HDM/DR is done.
1. Take down the `multi-validator` stack if it exists (the multi-validator does not support HDMs).
   Merge a PR to `main` that sets `export SPLICE_DEPLOY_MULTI_VALIDATOR=false` in the target cluster's `.envrc.vars`.
1. For a disaster recovery, test the `cncluster take_disaster_recovery_dumps` step below, against a timestamp determined from `sv-1` as with below.
1. Request PAM access some time before the scheduled time.
   Keep in mind a PAM request lasts for 4h, so you want to ensure you'll have PAM for the duration of the meeting.
1. Wait until the scheduled time has arrived. For hard domain migrations, the domain should be paused and a migration dump should be exported.
   If unsure, check the logs of the SV app (and any validator apps) for an entry such as "Wrote domain migration dump"
   (e.g., via [GCE Log Explorer](https://console.cloud.google.com/logs/query;query=resource.labels.cluster_name%3D%22cn-devnet%22%0A%22Wrote%20domain%20migration%20dump%22;summaryFields=resource%252Flabels%252Fnamespace_name:false:32:beginning;cursorTimestamp=2024-10-09T13:01:30.491233228Z;duration=PT30M?project=da-cn-devnet)).
1. For hard domain migrations: Wait until all our apps have fully caught up.
   For a good margin of safety, the last "Ingested transaction" log entry for each app should be >10 minutes old.
   It's probably easiest to check this via the [GCE Log Explorer](https://console.cloud.google.com/logs/query;query=resource.labels.cluster_name%3D%22cn-devnet%22%0A%22Ingested%20transaction%22;summaryFields=resource%252Flabels%252Fnamespace_name:false:32:beginning;cursorTimestamp=2024-10-11T04:41:04.651869226Z;duration=PT30M?project=da-cn-devnet).
   Once you have verified this, move onto the next step quickly (no need to wait for the go-ahead from the person leading the call).
1. Backup our SVs and validators (see [Backup](#backup)):
   ```
   cncluster backup_nodes <old_migration_id> true|false sv-1
   cncluster backup_nodes <old_migration_id> true|false sv-2
   cncluster backup_nodes <old_migration_id> true|false sv-3
   cncluster backup_nodes <old_migration_id> true|false sv-4
   cncluster backup_nodes <old_migration_id> true|false validator1
   cncluster backup_nodes <old_migration_id> true|false splitwell
   ```
   Our backups are slow, specifically the SV backups can take upto 10 mins. So you want to get started early and launch the commands in parallel.
   Note that our tooling currently doesn't support backing up our runbook nodes.
   If they break we need to redeploy them with empty state.
1. For MainNet: take note of the backup RUN_ID (you will find it in the success message of
   the backup, which will be of form "Completed all backups for namespace sv-1, RUN_ID = ...")
1. In the event of a disaster recovery, you need to agree on a timestamp (in the format “2024-04-17T19:12:02Z”) with the byzantine majority of the SVs.
   For Digital-Asset-2, only `sv-1` logs; you can use [mentions of other SVs in its logs](https://console.cloud.google.com/logs/query;query=resource.labels.namespace_name%3D%22sv-1%22%0Aresource.labels.container_name%3D%22participant%22%0AjsonPayload.message%3D~%22Commitment%20correct.*PAR::%2528DA-Helm%7CDigital-Asset-%25282%7CEng%2529%2529.*toInclusive%22;duration=PT1H?project=da-cn-devnet) to determine the minimum time for SVs 2-4 and DA-Helm-Test-Node.
   Then, execute the following commands:
    - `cncluster take_disaster_recovery_dumps <timestamp> <new_migration_id> <output_directory> sv-1 sv-2 sv-3 sv-4 sv validator validator1 splitwell`
    - `cncluster copy_disaster_recovery_dumps <dump_directory> sv-1 sv-2 sv-3 sv-4 sv validator validator1 splitwell`
1. Note (or take a screenshot of) the amulet balance of one of our SVs (for post-migration [sanity check](#new-domain-readiness-checks))
1. **Migrate:** Merge two PRs, against the target deployment branch (s.a.: [operator deployments](#operator-deployments)) and a forward-port to `main`, that modify the following config for your target cluster:
   * in `config.yaml` change `synchronizerMigration.active` to `synchronizerMigration.legacy`
   * in `config.yaml` change `synchronizerMigration.upgrade` to `synchronizerMigration.active`
   * in `config.yaml` set `synchronizerMigration.active.migratingFrom` to the migration ID we're migrating away from
   * sync the migration config found in `config.yaml` on all the branches referenced in any migrations `releaseReference` (this just ensure that some metrics are labeled as expected for still running migrations, and that it removes all the deployments for archived migrations)
   * Update the `CHARTS_VERSION` and `OVERRIDE_VERSION` in the target cluster env vars to the version we're migrating to.
1. Wait for the operator to apply your changes from the migrate step.
   The deployments might fail or time out if too few SVs have completed the migration to unpause the new domain.
   (Check the logs of failing pods to be sure that there is no other problem.)
   To get a sense for how many SVs still need to finish their post-migration init before the new synchronizer becomes operational,
   you can filter participant logs for `Persisted.*SynchronizerParametersState`
   ([gcloud logs example](https://console.cloud.google.com/logs/query;query=resource.labels.namespace_name%3D%22sv-1%22%0Alabels.%22k8s-pod%2Fapp%22%3D%22participant-1%22%0APersisted%0ASynchronizerParametersState;duration=PT15M?project=da-cn-devnet))
   and inspect the (number of) signatures on the latest of those entries
   (you need slightly over 2/3 of SVs to sign this).
1. [Check that the new domain is healthy and sound](#new-domain-readiness-checks).
   Communicate the result of your check to the rest of the DSO to conclude the migration.
   If enough SVs (>2/3) are not able to complete the migration successfully, we may need to roll-back to the previous domain.
   For instructions on how to do so, refer to [Switching back to the old domain](#switching-back-to-the-old-domain).
1. (non-DevNet clusters) Check to see that fresh node identities dumps have been taken after the migration. Check the logs of the SV app for an entry that says "Wrote node identities dump" [GCloud Logs example](https://console.cloud.google.com/logs/query;query=resource.labels.cluster_name%3D%22cn-testnet%22%0A%22Wrote%20node%20identities%20dump%22%0A;summaryFields=resource%252Flabels%252Fnamespace_name:false:32:beginning;cursorTimestamp=2024-10-11T05:19:14.578112132Z;duration=PT30M?project=da-cn-devnet)
1. **Post-migration:** Merge a PR against the target deployment branch (s.a.: [operator deployments](#operator-deployments)) that makes the following changes:
   * in `config.yaml` remove `synchronizerMigration.active.migratingFrom`
   * If periodic SV runbook re-deployments are scheduled for the target cluster, also set `DISABLE_COMETBFT_STATE_SYNC` to "false".
   * Check that the triggers work by triggering the jobs manually once.
1. Open a PR (for `main`) to re-enable all previously disabled checks and (re-)deployments.
1. Forward-port all your changes from the deployment branch to `main`.
1. Make sure that [validators](https://daholdings.slack.com/archives/C06QB1ZEGCE) are informed that the hard migration has been completed and that they should upgrade (if required) and configure the new migration ID.
1. For MainNet: Copy the very last backup you took from the old migration ID (after
   pausing the synchronizer) by running:
   `gcloud workflows execute copy-cn-backup-to-bucket --project da-cn-shared --location us-central1 --data '{"migrationId": <OLD migration ID>, "cnBackupRunId": "<backup run ID you took note of above>"}'`
1. **Cleanup:** Once you (much later) agree with the other DSO members that it's prudent to tear down all legacy components, you can do this by merging a PR against the target deployment branch that removes part of the changes from the previous steps:
   * in `config.yaml`, remove the `synchronizerMigration.legacy` section or move it to `synchronizerMigration.archived` if state from the old synchronizer should be preserved

##### Troubleshooting external stacks

Upgrading a cluster to use external Canton stacks leads to known failures that need to be addressed via manual steps.
The following steps need to be run before a Hard Domain Migration or a Disaster Recovery:

1. Change to the correct cluster directory. e.g. `cluster/deployment/devnet`
1. For each SV namespace, run the following command to refresh the Pulumi stack and generate the corresponding secret provider configuration:
   `CI=1 SPLICE_MIGRATION_ID=<migration-id> SPLICE_SV=<sv-namespace> cncluster pulumi sv-canton refresh --skip-preview --yes`
1. Commit the generated yaml files to `main` as well as to the branch you're deploying from.
   They should be of the form `Pulumi.sv-canton.<sv-namespace>-migration-<migration-id>.<cluster>.yaml`

If the operator complains that a stack doesn't exist, try refreshing manually.

If you (then) see error logs like:
```
error: constructing secrets manager of type "passphrase": constructing secrets manager: passphrase must be set with PULUMI_CONFIG_PASSPHRASE or PULUMI_CONFIG_PASSPHRASE_FILE environment variables
```
You need to commit the new Pulumi files generated by the manual refresh following the steps above.

#### Checking the readiness of partners

1. Partners self-report their readiness on [Slack](https://daholdings.slack.com/archives/C05E70BCSDA).

1. Partners have upgraded to the version we expect *before* the migration (i.e., they are not on an older version). Hacky oneliner:

   ```
   curl -s https://scan.sv-2.global.canton.network.digitalasset.com/api/scan/v0/scans | jq '.scans.[].scans.[].publicUrl' -r | xargs -n 1 sh -c 'echo -n "$0 ";  curl -s $0/api/scan/version | jq \'.version\''
   ```

1. Partners' new CometBFT nodes are reachable (before the actual migration has taken place).
   Check that the list of SVs returned by `cncluster list_cometbft_peers <new_migration_id>` matches the expected list of SVs for the corresponding network.
   Note that this command lists only the peers of our SV-1 CometBFT node so the output will not contain an entry for SV-1 itself.

1. Partners' new sequencer nodes are reachable (before the actual migration has taken place). Hacky oneliner (with explanations below).
   ```
   curl -s https://sv.sv-2.$GCP_CLUSTER_HOSTNAME/api/sv/v0/dso | \
     jq '.sv_node_states.[].contract.payload.state.synchronizerNodes.[0].[1].sequencer.url | sub("sequencer-<old_migration_id>"; "sequencer-<new_migration_id>") | sub("https://"; "")' -r | \
     xargs -n 1 sh -c 'echo $0; grpcurl --max-time 10 $0:443 grpc.health.v1.Health/Check; echo'
   ```

   This assumes that we're running this check from an IP range that should be whitelisted and that the partners follow our recommended migration ID-based URL scheme for sequencers.
   So use this with many grains of salt and clarify with partners for which of the checks fail that our assumptions are correct in their case.

   Also note that pre-migration, the sequencer URLs of correctly set up sequencers can return one of the following errors (and this is fine):

   ```
   Error invoking method "grpc.health.v1.Health/Check": rpc error: code = Unavailable desc = failed to query for service descriptor "grpc.health.v1.Health": upstream connect error or disconnect/reset before headers. reset reason: remote connection failure, transport failure reason: delayed connect error: 111
   Error invoking method "grpc.health.v1.Health/Check": failed to query for service descriptor "grpc.health.v1.Health": server does not support the reflection API
   Error invoking method "grpc.health.v1.Health/Check": rpc error: code = Unavailable desc = failed to query for service descriptor "grpc.health.v1.Health": unexpected HTTP status code received from server: 502 (Bad Gateway)
   Error invoking method "grpc.health.v1.Health/Check": rpc error: code = DeadlineExceeded desc = failed to query for service descriptor "grpc.health.v1.Health": context deadline exceeded
   Failed to dial target host "sequencer-3.sv-1.test.global.canton.network.sync.global:443": EOF
   ```

   We expect to get an error here because the sequencer's public API should not be initialized yet. Other errors on this connection might also be fine.
   Results of this check that are certainly not fine include the sequencer reporting to be `SERVING` (check whether the sv app was properly configured to perform a migration) or us hitting an HTML page instead of an gRPC endpoint (possible ingress misconfiguration).
   If in doubt, reach out to the relevant SV partner on [#supervalidator-operations](https://daholdings.slack.com/archives/C085C3ESYCT) or ask for help in the internal channel.

#### New domain readiness checks

1. We have our expected amulet balance.
1. "Commitment correct" logs are visible from the other SVs via the new participant [GCloud Logs Example](https://console.cloud.google.com/logs/query;query=resource.labels.cluster_name%3D%22cn-devnet%22%0Alabels.%22k8s-pod%2Fmigration_id%22%3D%221%22%0A%22commitment%20correct%22%0A;summaryFields=resource%252Flabels%252Fnamespace_name:false:32:beginning;cursorTimestamp=2024-10-11T04:46:01.562157559Z;duration=PT30M?project=da-cn-devnet).
1. Alls SVs are in sync based on the "SV Status Reports" [Grafana dashboard](#prometheus-metrics-and-grafana-dashboards).
1. All our partners confirm that they have their expected amulet balance and that they aren't seeing anything weird.

See [Network Health](./network-health/NETWORK_HEALTH.md) for further investigation if any of these checks fail.

#### Switching back to the old domain

1. Make sure a consensus to unpause the old domain has been reached among the SV operators
2. Revert your previous PR on the target deployment branch that triggered the hard domain migration.
3. Obtain an authentication token by logging in to the SV UI and copying the value in the Authorization header.
   ```
   export AUTH_TOKEN="Bearer <long-token-string>"
   ```
4. Unpause the old domain. To do this, run the following command:
   ```
   curl -L -H "Authorization: $AUTH_TOKEN" https://sv.sv-2.$GCP_CLUSTER_HOSTNAME/api/sv/v0/admin/domain/unpause
   ```
5. [Check that the old domain is once again functional](#new-domain-readiness-checks).
   Communicate the result of your check to the rest of the DSO to conclude the migration.
6. **Cleanup:** Tear down the new migration ID nodes so that they don't get in the way the next time we want to migrate.
   To do so, you can revert your original PR staging the new nodes such that only the following environment variables remain:
    * `export CHARTS_VERSION=` the original version we were migrating away from
    * `export GLOBAL_DOMAIN_ACTIVE_MIGRATION_ID=` the original migration ID we were migrating away from

## Interacting with Canton Network UIs

To login to the following UIs use our test credentials from [our list of passwords](https://docs.google.com/document/d/1ajR8_SsSybl6GSrhGggOHEZPfCF0hzk0MDJMyziV7Vc/edit?ouid=103930368588823687273&usp=docs_home&ths=true):

| Endpoints                                                  | Description                                                                                            |
|------------------------------------------------------------|--------------------------------------------------------------------------------------------------------|
| `https://sv.sv-2.<CLUSTER>.network.canton.global/`     | Admin user interface for Sv Operator to find information about the collective and perform admin tasks. |
| `https://wallet.sv-2.<CLUSTER>.network.canton.global/` | User interface for the validators to transfer money and manage applications.                           |


## Interacting with Canton Network APIs

It is possible, although not always convenient, to access Canton and app APIs deployed on our cluster.
This can be useful for debugging, for checking network state not yet exposed in other ways, as well as for
[fixing a running network](#strategies-for-reacting-to-a-failed-testnet-or-devnet-deployment).

### Canton Participant, Sequencer and Mediator APIs

1. `cd` into a cluster directory of your choice.
2. Run `cncluster participant_console <namespace>`,
   substituting `<namespace>` with the namespace in which your target participant is running in.

This will attempt to obtain a Ledger API token from Auth0,
set up k8s port forwarding of relevant ports to your local machine,
and start a local Canton console that connects to these ports.
You can also set the `LEDGER_API_AUTH_TOKEN` environment variable manually (see [below](#app-apis-with-authentication))
before running the command,
in case obtaining a ledger API auth token automatically fails.

What you get in the end is a Canton console with one `participant` reference that you can use for accessing admin API and ledger API functionality.
For example, you can check which mediators are currently onboarded as per topology state:

```
@ participant.topology.mediators.list()
```

Or you can use the ledger API to archive a contract:

```
// Get the svParty
val svParty = participant.ledger_api.parties.list().filter(_.isLocal).filter(_.party.toProtoPrimitive.startsWith("Canton"))(0).party

// Get the contract to archive (double check that it's the one you want)
val contract = participant.ledger_api.acs.of_party(svParty, filterTemplates=Seq(TemplateId("", "Splice.SvOnboarding", "ApprovedSvIdentity")))(0)

// Build the archival command
import com.daml.ledger.javaapi.data._
val archiveCommand = new ExerciseCommand(new Identifier("", "Splice.SvOnboarding", "ApprovedSvIdentity"), contract.event.contractId, "Archive", new DamlRecord())

// Submit it (getting an error here doesn't have to mean that this failed)
participant.ledger_api.commands.submit(actAs=Seq(svParty), commands=Seq(com.daml.ledger.api.v2.commands.Command.fromJavaProto(archiveCommand.toProtoCommand)))

// Verify that the contract is gone
participant.ledger_api.acs.of_party(svParty, filterTemplates=Seq(TemplateId("", "Splice.SvOnboarding", "ApprovedSvIdentity"))).filter(_ == contract)
```

Note that above example will likely not work out of the box by the time you attempt to replicate it.
In addition to the documentation available from within the Canton console (try hitting Tab after spelling out a command name),
the `LedgerApiAdministration.scala` and `ParticipantAdministration.scala` files in the Canton source tree (/ our fork)
contain helpful pointers for interacting with the Canton APIs.

You can also access sequencer and mediator:

1. `cd` into a cluster directory of your choice.
2. Run `cncluster sequencer_console <namespace>` or `cncluster mediator_console <namespace>`,
   substituting `<namespace>` with the namespace in which your target node is running in.

### App APIs without authentication

Just use `curl`! For example, here is how to get the current SVs (as per the `DsoRules`) from SV1 on DevNet:

```
curl https://sv.sv-2.dev.global.canton.network.digitalasset.com/api/sv/v0/dso | jq '.dso_rules.payload.svs'
```

### App APIs with authentication

We again use `curl`, but this time we also need an auth token from Auth0.
See the implementation of `cncluster participant_console` for ideas on how to obtain this programmatically.

For obtaining a token manually, you can use the Auth0 dashboard.
Here is one way:

1. On the Auth0 website, navigate to the [tenant](#auth0-tenantsapplications) that is relevant to the cluster and API you want to access.
2. Go to APIs -> Ledger API (the tokens we get here can be used for our apps too) -> Test
3. Select an appropriate application in the dropdown menu.
   For accessing the SV API of SV1, for example, you might want to choose an application with a name like "SV1 backend".
4. This page now shows code snippets for obtaining a compatible token from Auth0,
   and even an actual token that you can just copy paste.

Assuming that you saved your token in the `$TOKEN` environmant variable, you can now, for example,
prepare a validator onboarding via SV1's SV API:

```
export TOKEN="What you got from Auth0"
curl -sSL --fail-with-body "https://sv.sv-2.dev.global.canton.network.digitalasset.com/api/sv/v0/admin/validator/onboarding/prepare" -d "{\"expires_in\": \"1000\"}" -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json"
```

For the administrator user, you can get a token for validator apps, SV apps and
splitwell using the `cncluster get_token <namespace> <app>` command, e.g.

```
export TOKEN=$(cncluster get_token sv-1 sv)
```

## Configuring a New GCP Project

Rarely, there may be a need to configure a new GCP project for Canton
Network. Steps to do this are as follows:


1. Request that a project be created by sending a mail to `help@digitalasset.com`.
    * The project should be derived from an existing CN project, and given the organization "'no
   organization'."
    * Rights to this project should also be granted to `team-canton-network@digitalasset.com`.
    * The team group should be granted (at minimum) the following IAM roles:
      - Editor
      - Project IAM Admin
      - Secret Manager Admin (* optional: with IAM Admin, we can grant this to ourselves if needed)
      - Compute Network Admin (* optional: with IAM Admin, we can grant this to ourselves if needed)
      - Kubernetes Engine Admin (* optional: with IAM Admin, we can grant this to ourselves if needed)
2. Create a `deployment` directory for a cluster in the new
   project. This directory can be populated with `.envrc` and
   `.envrc.vars` from another deployment directory, but environment
   specific configuration should be updated in `.envrc.var`. A few
   variables to consider are these:
      * `CLOUDSDK_CORE_PROJECT` - This should be the name of the newly
        created GCP project.
      * `PULUMI_BACKEND_URL` - This should be the `gs` URI for the
        Pulumi backend bucket
3. Change into the new deployment directory, and run `cncluster gcp_up` to configure the GCP project.
4. Start creating a new cluster with `cncluster create`. Once this
   command starts working, you'll see in the GCE web UI that a new
   default service account has been created. It'll have a principal of
   the following form: '816347582626-compute@developer.gserviceaccount.com'.
5. Run `cncluster activate` to authenticate to the project.
6. Add a role binding to enable the new default service account to
   have access to `da-cn-shared. The command to do this will look like
   this:

   ```
   gcloud projects add-iam-policy-binding da-cn-shared \
      --member='serviceAccount:816347582626-compute@developer.gserviceaccount.com' \
      --role='roles/artifactregistry.serviceAgent'
   ```
   ```
   gcloud projects add-iam-policy-binding da-cn-shared \
      --member='serviceAccount:816347582626-compute@developer.gserviceaccount.com' \
      --role='roles/storage.objectViewer'
   ```
   TODO(#9679) -- once pulumi fully manages the cluster creation, these IAM bindings can be automated.
7. Create a key to the CircleCI service account in GCP, copy the downloaded private key (full json) into
   a new environment variable in CCI. Search in cci's config files for one of the existing ones (e.g. GCP_DA_CN_SCRATCHNET_KEY)
   and add the new one.

8. Prod projects (currently da-cn-mainnet and da-cn-devnet) should be protected from
   accidental deletion using a lien
   (see https://cloud.google.com/resource-manager/docs/project-liens):

   ```
   gcloud alpha resource-manager liens create --reason="This project is protected by a lien" --restrictions=resourcemanager.projects.delete
   ```

<!-- TODO(#10061) Automate everything below -->

9. Grant permissions to the CCI account to access the DNS secret (todo: do this in Pulumi)

   ```
   gcloud projects add-iam-policy-binding $CLOUDSDK_CORE_PROJECT \
      --member='serviceAccount:circleci@${CLOUDSDK_CORE_PROJECT}.iam.gserviceaccount.com' \
      --condition='expression=resource.name.endsWith("secrets/dns01-sa-key-secret/versions/1"),title=DNS SA Key Secret' \
      --role='roles/secretmanager.secretAccessor'
   ```
10. Grant permissions to the CCI account to access the GCP bucket secret.

   ```
   gcloud projects add-iam-policy-binding $CLOUDSDK_CORE_PROJECT \
      --member='serviceAccount:circleci@${CLOUDSDK_CORE_PROJECT}.iam.gserviceaccount.com' \
      --condition='expression=resource.name.endsWith("secrets/gcp-bucket-sa-key-secret/versions/1"),title=GCP bucket Secret (version 1)' \
      --role='roles/secretmanager.secretAccessor'
   ```

   ```
   gcloud projects add-iam-policy-binding $CLOUDSDK_CORE_PROJECT \
      --member='serviceAccount:circleci@${CLOUDSDK_CORE_PROJECT}.iam.gserviceaccount.com' \
      --condition='expression=resource.name.endsWith("secrets/gcp-bucket-sa-key-secret/versions/latest"),title=GCP bucket Secret (latest version)' \
      --role='roles/secretmanager.secretAccessor'
   ```

11. Similar to 6, grant permissions also to the new CCI account:

   ```
   gcloud projects add-iam-policy-binding da-cn-shared \
      --member='serviceAccount:circleci@${CLOUDSDK_CORE_PROJECT}.iam.gserviceaccount.com' \
      --role='roles/storage.objectViewer'
   ```
12. Grant permissions to the shared kms to the new CCI account:

   ```
   gcloud projects add-iam-policy-binding da-cn-shared \
      --member serviceAccount:circleci@${CLOUDSDK_CORE_PROJECT}.iam.gserviceaccount.com \
      --role "roles/cloudkms.cryptoKeyEncrypterDecrypter" \
      --condition=expression='resource.type == "cloudkms.googleapis.com/CryptoKey" && resource.name.startsWith("projects/da-cn-shared/locations/'${CLOUDSDK_COMPUTE_REGION}'/keyRings/pulumi")',title="pulumi kms"
   ```

13. Grant `Editor` permissions to the GKE node pool account

This is required for logging to work among other things.

14. Add service account da-gcr-image-pull@da-dev-gcp-it-security-project.iam.gserviceaccount.com with "Kubernetes Engine Viewer" permissions to the project

15. Create an `artifactory-keys` secret in Secret Manager, consisting of the artifactory SA
    credentials (simply copy the content of the secret from another project).

16. Optionally, if a cluster in this project will be containing KMS-enabled Canton node deployments managed by the Pulumi operator, you also need to grant the following permissions to the CCI account:

  ```
  for role in [ iam.serviceAccountCreator iam.serviceAccountKeyAdmin iam.serviceAccountViewer ]; do
    gcloud projects add-iam-policy-binding "$CLOUDSDK_CORE_PROJECT" \
      --member="serviceAccount:circleci@${CLOUDSDK_CORE_PROJECT}.iam.gserviceaccount.com" \
      --role="roles/$role" \
      --condition=None
  done;

  echo '{ "expression": "api.getAttribute(\"iam.googleapis.com/modifiedGrantsByRole\", []).hasOnly([\"roles/cloudkms.admin\", \"roles/cloudkms.cryptoOperator\"])", "title": "KMS-related role bindings" }' | \
    gcloud projects add-iam-policy-binding "$CLOUDSDK_CORE_PROJECT" \
      --member="serviceAccount:circleci@${CLOUDSDK_CORE_PROJECT}.iam.gserviceaccount.com" \
      --role='roles/resourcemanager.projectIamAdmin' \
      --condition-from-file=-
   ```

As an initial look into the security posture of the docker images produced, the security team is scanning the images with BlackDuck.
The list of images to scan is generated by pulling a list of images from the CIDaily cluster.
Image scans will be added to the DA_IMAGE_SCAN project in blackduck.


## Creating a New Cluster

To create a new cluster, you need to follow these steps:

1. Create a `deployment` directory for the cluster, e.g. `cluster/deployment/cinew`.
2. Copy the `.envrc` and `.envrc.vars` files from an existing deployment directory,
   and update the environment specific configuration in `.envrc.vars`. Specifically:
     * `GCP_CLUSTER_BASENAME` - the name of the cluster. E.g., in the example above, `cinew`.
     * `CLOUDSDK_CORE_PROJECT` - the name of GCP project where the cluster belongs. E.g. `da-cn-ci-2`.
     * `GCP_MASTER_IPV4_CIDR` - a free IP range. You can find all the taken ones by searching for `GCP_MASTER_IPV4_CIDR` in the `.envrc.vars` of the other clusters.
     * Other variables might also need to be updated, depending on the specific requirements of the cluster.
3. Run `cncluster create` in the directory of the cluster (e.g., `cluster/deployment/cinew`).
4. The above should've created some Pulumi files of the form `Pulumi.$STACK.$CLUSTER.yaml`, e.g. `Pulumi.infra.cinew.yaml`.
   Commit them and push them to the repository.

If the cluster is a new scratchnet, in `.circleci/config/workflows/deploy_scratchnet_workflow.yml` you should also update:
* `deploy_scratchnet_basic`
* `deploy_scratchnet_DR`
to include the `hold_scratchnet` and `release_hold_set_scratchnet` jobs for the new cluster.

## Testing

### Writing Tests against different Clusters

1. Look for the right filename.
   - NonDevNet clusters (e.g. testnet, testnet-preview) include `NonDevNet` in the test class name,
   - Tests running preflight checks include `PreflightIntegrationTest`
   - Tests running the sv runbook preflight  checks include `PreflightSvIntegrationTest`
   - ... to see all the different pattern, good starting point is [build.sbt](https://github.com/DACH-NY/canton-network-node/blob/6a24a83724ad666d8095ff59cfcb00be879ad289/build.sbt#L1060)
2. Check that your test will be run by the right circle-ci job in the right workflow
   - see the different steps in `.circleci/config/workflows.yml` and `.circleci/config/prelude.yml`
   - run [`build-config.sh`](/.circleci/build-config.sh) to update the main config file


### Patching healthchecks against a deployed cluster

Our periodic healthchecks are triggered by CircleCI on `deployment/<cluster>` branches.
In case you need to patch the tests without redeploying the cluster, create a new branch based of
the `deployment/<cluster>` branch you want to patch, cherry-pick your fixes onto it and open a PR
against the deployment branch.

## Backup and Recovery

On most clusters, especially the long-running ones, all persistent storage is backed up
regularly, and we maintain a restore script for restoring from backup in case of data
corruption, or other unrecoverable state. CloudSQL instances are also configured to support
point-in-time recovery for finer granularity, but our scripts are not currently designed to  use that.

Note that there are certain ordering requirements on backups, which are all addressed
automatically in our scripts.

### Backup

The [node-backup.sh script](scripts/node-backup.sh) can back up one or more components in a
node (where a "node" in this context is a single SV or a single validator). It produces a set
of backups of all relevant persistent storage, identified by a RUN_ID. These backups are
guaranteed to be consistent in terms of ordering requirements.

This script can also be invoked from `cncluster` through `cncluster backup_nodes <migration_id> <internal_stack> <node...>`,
where `internal_stack` is true if the nodes are deployed through the internal stack (canton-network), and false otherwise, and
where `node` can be one (or more) of {sv-1, sv-2, sv-3, sv-4, validator1, splitwell}.

For every node, at the end of the backup process, the script will report something like:
```
Info: Completed all backups for namespace sv-3, RUN_ID = 1704915116
```

This RUN_ID is an identifier of the backup run, and can later be used for restoring backups.

cidaily, cilr and the prod clusters are backed up periodically through CircleCI, where the RUN_IDs of the latest backup can be found.

### Restore

Backups created through `node-backup.sh` (or the corresponding `cncluster backup_nodes`
command) can be used for recovery using the `node-restore.sh` command.

To do that, run: `node-restore.sh <namespace> <migration_id> <run_id> <internal_stack> <component...>`,
where `internal_stack` is true if the nodes are deployed through the internal stack (canton-network), and false otherwise, and
where `component` can be one or more out of {validator, participant} for a validator and one or more out of
{validator, scan, sv-app, participant-<migration_id>, mediator, sequencer, cometbft-<migration_id>} for an SV.

Most components can, in general, be restored independently of others.

The only strict requirement is that if a participant is restored, then all CN apps
(validator, sv-app, scan) must be restored too.

In certain cases, one may need to restore more than one component. For example, if a participant
in an SV node declares a sequencer to be forked, then both the sequencer and the participant
will need to be restored from a backup before that point. Restoring only the sequencer will not
"convince" the participant to trust that sequencer again. The dependencies between components is
as follows:

```
cometbft -> sequencer -> mediator
                      \
                       -> participant -> {validator, sv, scan}
```

Current caveats:
- Currently, Pulumi will not be aware of any changes performed by the recovery script,
  so Pulumi commands applied to the same cluster at a later point will most probably go wrong
  in many ways.
- Command deduplication on the participant may, in theory, break after a recovery. However, for
  this to happen, an app must be up and running within 1 minute (the default
  ledger-time-record-time-tolerance of a domain) from the time the participant
  submitted its last command before going down for the backup restore. Since that is highly
  unlikely, we choose to ignore this potential corner case.
- While a component is catching up on everything that happened after the point of recovery,
  it will typically report healthy, but in fact will be busy catching up.
  - To see the latest block known to the CometBFT node, browse to the SV UI, navigate to
    the CometBFT debug info tab, and inspect the last_block_height. You can compare the height
    of the recovering node to that observed in other SVs.
  - To see the latest block handled by the sequencer, search for log messages containing
    "Handle block with height" in the sequencer's output.
  - The sequencer, mediator and participant also expose a `canton.<component>.sequencer-client.delay`
    metric, which compares the sequencing time of the latest processed sequencer message with the local
    clock of the component. High values of this metric indicate that this component is catching up.

## Chaos Mesh

You can configure a cluster to deploy [Chaos Mesh](https://chaos-mesh.org/) for chaos-monkey style testing.
This is currently disabled by default. To enable it, set

```
export ENABLE_CHAOS_MESH=1
```

## Maintenance Windows

GKE clusters are automatically updated by Google. This can cause downtime and trigger false positive alerts.
To avoid this we set maintenance windows during working hours as followed:
- Tuesdays from 8:00 AM to 12:00 AM UTC
- Wednesdays from 8:00 AM to 12:00 AM UTC
- Thursdays from 8:00 AM to 12:00 AM UTC

These are set when the cluster is created and can be changed using:

```
gcloud container clusters update "${GCP_CLUSTER_NAME}" \
       --maintenance-window-start 2024-01-01T08:00:00 \
       --maintenance-window-end 2024-01-01T12:00:00 \
       --maintenance-window-recurrence 'FREQ=WEEKLY;BYDAY=TU,WE,TH'
```

A gcp alert triggers whenever an update starts.

## Multi-architecture Docker Images

When built locally using `make docker-build`, our images are built for running in k8s on amd64. When built from CI, they support also running on arm64.

When modifying Dockerfiles, keep in mind that they need to support multi-arch. The main thing
to keep in mind is the distinction between $BUILDPLATFORM (the architecture on which the `docker build` command is running, i.e. your machine, or the CCI docker_build job) and
$TARGETPLATFORM (the architecture for which the image is built, i.e. on which the container
will run). We utilize multi-stage builds (see https://docs.docker.com/build/building/multi-stage/) to support this distinction. Typically, all RUN commands in the Dockerfile
will need to run in the context of $BUILDPLATFORM, i.e. in a stage that uses `--platform $BUILDPLATFORM` because they are executed at build time, and in the end copied over to a
stage that builds the final image for $TARGETPLATFORM (the default for FROM if no
`--platform` is specified).

To build multi-arch images locally:
- Enable containerd image store, per https://docs.docker.com/build/building/multi-platform/#enable-the-containerd-image-store.
  Note that this hides any existing images, running containers, etc. but they are still there
  and the step is reversible.
- `export CI=1`, and build the images using `make docker-build -j`

## Docker-Compose

Similarly to `cncluster`, we have a `splice-compose.sh` script for development purposes of
the docker-compose validator deployment.

The two most useful subcommand there are `splice-compose.sh start` and `splice-compose.sh start_network`.

`start` starts a validator against an existing network. It should be run from a
cluster deployment directory, and will spin up a docker-compose validator against that
cluster. Logs from the validator and participant will be streamed to `logs/compose-validator.clog`
and `logs/compose-participant.clog` resp.
Use `splice-compose.sh stop` to stop it (and `splice-compose.sh stop -D` to completely
nuke it, i.e. also delete its persistent data).

`start_network` starts a full CN deployment locally, consisting of one SV node and one
validator connected to it. Similarly to `start`, logs from the different components will
be streamed to `logs/compose-sv-*.clog` for SV node components, and `logs/compose-*.clog`
for those of the validator. To stop the whole network, run `splice-compose.sh stop_network`
(with an optional `-D` as above, to also wipe all its data).

## Onboarding

### Polling interval

To speed up SV initialization at the expense of fairly noisy logs you can set this option:

```
export SV_ONBOARDING_POLLING_INTERVAL=5s
```

### Onboarding Participant Promotion Delay (set by default)

To remove the delay added to the trigger that promotes participants to submitter status and respectively updates the Party
to Participant threshold you can set the following option. This is enabled by default on scratchnets to speed up deployment.

```
export DISABLE_ONBOARDING_PARTICIPANT_PROMOTION_DELAY=true
```

### Correlate cometbft node address to the SV that runs the node
When you are debugging cometbft logs, it can be useful to correlate the cometbft node address back to the SV
that is running that node.
For instance, if you find that blocks take a long time to advance in cometbft logs, and you want to relate this to an SV running cometbft nodes.

To debug what is happening while a slowdown is occurring, poll the `consensus_state` endpoint
while blocks are advancing slowly (the `consensus_state` does not provide historical data).

You can access this endpoint as follows:

```
curl -fsSL -X POST -H 'Content-Type: application/json' --data '{"id": 0, "method": "consensus_state"}' "https://sv.sv-2.${GCP_CLUSTER_HOSTNAME}/api/sv/v0/admin/domain/cometbft/json-rpc" | jq
```

The cometbft `consensus_state` endpoint provides the result state that the state machine has currently reached in the consensus protocol.
It provides the `round_state`, which specifies the block `height/round/step` and its prevotes and precommits, as well
as the address of the proposer.

The example command below connects to `sv-1` and lists the SVs with their pub_key and cometbft addresses:

```
cncluster list_sv_cometbft_addresses sv-1
```

The output should look something like the example below:

```
[
  {
    "address": "9CF584AC1D1F0A8661F9BC7E072B99265AD393D6",
    "pub_key": "12GfyR5a/2F1buayWs+zO82NebzvcbvvH3ZhTWRMaBQ=",
    "sv": "SBI-Holdings",
  },
  {
    "address": "DE36D23DE022948A11200ABB9EE07F049D17D903",
    "pub_key": "2umZdUS97a6VUXMGsgKJ/VbQbanxWaFUxK1QimhlEjo=",
    "sv": "Digital-Asset-Eng-4",
  },
  ...
]
```

This output can be used to find the proposer address from the `consensus_state` endpoint and find the associated SV.

## Testing Performance-Critical Changes

Some changes require extra performance testing before making it into a
release. Notable examples include infrastructure changes (switching
node types, databases, …) or Canton upgrades that may have a
performance impact (all major upgrades in particular).

We use `cilr` for performance testing which runs slightly above devnet scale (currently 16 SVs and 200 validators) and with
a load test of 1 CC p2p transfer/s.

Validating performance consists of two steps:

1. Upgrade CILR to contain the version/configuration you want to test.
2. Validate that performance matches what you expect.
   1. First check the [load
      test](https://grafana.cilr.global.canton.network.digitalasset.com/d/ccbb2351-2ae2-462f-ae0e-f2c893ad1028/k6-load-tester-custom-metrics?orgId=1&refresh=5s)
      dashboard and ensure that we can keep the target rate of 1 CC
      tx/s and latency has not increased.
   2. Run a sequencer catchup test. Sequencers are usually our bottleneck for performance so we specifically isolate how fast they can catch up.
      To do so
      1. pick a sequencer (usually any other than SV-1 is a good choice as that is the DSO delegate).
      2. Scale down the sequencer deployment, e.g., `kubectl scale deployment -n sv-$i global-domain-3-sequencer --replicas=0`.
      3. Find the database in [GCP](https://console.cloud.google.com/sql/instances?project=da-cn-ci-2) (filter by cluster and namespace)
      4. Restore the database from a backup that is at least 12h old through the UI.
      5. Scale up the sequencer `kubectl scale deployment -n sv-$i global-domain-3-sequencer --replicas=1`
      6. Check catchup performance in the [dashboard](https://grafana.cilr.global.canton.network.digitalasset.com/d/ca9df344-c699-4efe-83c2-5fb2639d96d9/global-domain-catchup?orgId=1&refresh=30s).
         As a rough guideline anything below 4x is concerning but double check what the current expected numbers are in #team-canton-network-internal.

## Testing compatibility of Dev/Test/Mainnet topology with the next major Canton version

Compatibility of the topology snapshot across major Canton versions is
essential for the hard synchronizer migration to succeed.  It can
therefore be useful to test this in advance before actually attempting
the hard synchronizer migration. To do so, you can create exports
explicitly and then try to initialize a sequencer locally from the
export. The resulting sequencer will not be fully functional
afterwards since you lack the other SVs but it is enough to go through
the validation of the topology state in Canton. The concrete steps are:

### Export the data from the cluster you're validating

1. create a directory to store the state export: `mkdir -p /tmp/state-export/keys`
2. Switch to the current release branch of the cluster you are migrating
3. Start sequencer console on the version currently on the cluster connected to the target
   cluster, e.g. `cncluster sequencer_console sv-1 <migration id>` run from
   `cluster/deployment/<cluster>`. Adjust migration id to the current
   migration id on the cluster and the directory to the cluster you
   want to test. Note that you need to request PAM for that.
4. Export the keys
   ```
   sequencer.keys.secret.list().foreach(k => sequencer.keys.secret.download_to(k.publicKey.fingerprint, s"/tmp/state-export/keys/${k.publicKey.fingerprint}"))
   ```
   Note: We only export the sequencer keys which are relatively harmless and do not provide access to coin holdings. We also
   delete them afterwards and this is only possible after requesting PAM.
5. Export the genesis state (roughly the synchronizer topology state)
   ```
   sequencer.topology.transactions.genesis_state(sequencer.synchronizer_id.filterString).writeTo(new java.io.FileOutputStream("/tmp/state-export/genesis-state"))
   ```
6. Save the number of currently active topology transactions for future validation
   ```
   sequencer.topology.transactions.list(store = sequencer.synchronizer_id, proposals = false, timeQuery = TimeQuery.HeadState).result.groupMapReduce(_.mapping.code)(_ => 1)(_ + _)
   ```
   Note: This should be run immediatly after exporting the genesis state to ensure no new topology state is written between the genesis export and the current state calculations.
7. Export the authorized topology store snapshot
   ```
   sequencer.topology.transactions.export_topology_snapshot(TopologyStoreId.Authorized, filterMappings = Seq(TopologyMapping.Code.NamespaceDelegation, TopologyMapping.Code.OwnerToKeyMapping, TopologyMapping.Code.IdentifierDelegation, TopologyMapping.Code.VettedPackages), filterNamespace = sequencer.id.namespace.filterString, timeQuery = TimeQuery.Range(None, None)).writeTo(new java.io.FileOutputStream("/tmp/state-export/authorized"))
   ```
8. Get the sequencer id from `sequencer.id.toProtoPrimitive` and save it

### Initialize the new sequencer from the exports
1. Switch to the branch of the target version you want to migrate to
2. Disable auto-init of `globalSequencerSv1` by applying the patch in `simple-topology-canton.conf`.
```
Index: apps/app/src/test/resources/simple-topology-canton.conf
IDEA additional info:
Subsystem: com.intellij.openapi.diff.impl.patch.CharsetEP
<+>UTF-8
===================================================================
diff --git a/apps/app/src/test/resources/simple-topology-canton.conf b/apps/app/src/test/resources/simple-topology-canton.conf
--- a/apps/app/src/test/resources/simple-topology-canton.conf	(revision cf0ada7cbcecbffea34d11a14750ca993430ab89)
+++ b/apps/app/src/test/resources/simple-topology-canton.conf	(date 1734097962508)
@@ -145,7 +145,8 @@
     splitwellParticipant.init.identity.node-identifier.name = "splitwellValidator"
   }
   sequencers {
-    globalSequencerSv1 = ${_sequencer_reference_template} ${_sv1Sequencer_client} ${_autoInit_enabled}
+    globalSequencerSv1 = ${_sequencer_reference_template} ${_sv1Sequencer_client}
+    globalSequencerSv1.init.identity.node-identifier.type = "explicit"
     globalSequencerSv1.storage.config.properties.databaseName = "sequencer_sv1"
     globalSequencerSv1.sequencer.config.storage.config.properties.databaseName = "sequencer_driver"
     globalSequencerSv1.init.identity.node-identifier.name = "sv1"
```
3. Start canton using `./start-canton.sh -w` and switch to the tmux session running the Canton console.
4. Double check the version using `com.digitalasset.canton.buildinfo.BuildInfo.version`. This should be the canton version for the CN version you are migrating to.
5. Check that sequencer is not initialized
    ```
    @ globalSequencerSv1.id
    {"@timestamp":"2024-10-31T07:46:10.258Z","@version":"1","message":"Node is not initialized and therefore does not have an Id assigned yet.\n  Command SequencerReference.id invoked from cmd0.sc:1","logger_name":"c.d.c.c.EnterpriseConsoleEnvironment","thread_name":"main","level":"ERROR","level_value":40000}
    com.digitalasset.canton.console.CommandFailure: Command execution failed.
    ```
6. Import the keys
    ```
    better.files.File("/tmp/state-export/keys").glob("*").foreach(f => globalSequencerSv1.keys.secret.upload_from(f.toString, None))
    ```
7. Initialize the sequencer with the sequencer id you previously saved (swap out the ID in the command)
    ```
    globalSequencerSv1.topology.init_id(SequencerId.fromProtoPrimitive("<sequencer ID (SEQ::...)>", "").right.get.uid)
    ```
8. Import the authorized store snapshot
    ```
    globalSequencerSv1.topology.transactions.import_topology_snapshot_from("/tmp/state-export/authorized", TopologyStoreId.Authorized)
    ```
9. Initialize the sequencer from the genesis state
    ```
    globalSequencerSv1.setup.assign_from_genesis_state(com.google.protobuf.ByteString.readFrom(new java.io.FileInputStream("/tmp/state-export/genesis-state")), StaticSynchronizerParameters.defaults(globalSequencerSv1.config.crypto, ProtocolVersion.latest))
    ```
10. Verify that the sequencer is initialized
    ```
    @ globalSequencerSv1.health.status
    res18: NodeStatus[globalSequencerSv1.Status] = Sequencer id: global-domain::122084177677350389dd0710d6516f700a33fe348c5f2702dffef6d36e1dedcbfc17
    Domain id: global-domain::122084177677350389dd0710d6516f700a33fe348c5f2702dffef6d36e1dedcbfc17
    Uptime: 10.552838s
    Ports:
            public: 5108
            admin: 5109
    Connected participants: None
    Connected mediators: None
    Sequencer: SequencerHealthStatus(active = true)
    details-extra: None
    Components:
            db-storage : Ok()
            sequencer : Ok()
    Accepts admin changes: true
    Version: 3.2.0-SNAPSHOT
    Protocol version: 32
    ```
11. Calculate the number of currently active topology transactions after full init
   ```
   globalSequencerSv1.topology.transactions.list(filterStore = globalSequencerSv1.synchronizer_id.filterString, proposals = false, timeQuery = TimeQuery.HeadState).result.groupMapReduce(_.mapping.code)(_ => 1)(_ + _)
   ```
   Compare these numbers with the numbers from the cluster sequencer. The numbers should be identical.
12. Check that there are no sketchy warnings or errors in the Canton logs in `log/canton.clog`
13. Delete the export `rm -r /tmp/state-export`


## Simulate a long-running cluster on a scratchnet

This section describes how to set up a scratchnet where:

1. the network was deployed using a chosen release version
2. the network has completed one HDM
3. one SV has joined at some arbitrary time after the HDM
4. selected nodes have upgraded to the latest version from your local code

Instructions:

- Lock a scratchnet. The instructions below assume you have locked `scratchneta`.
- Configure the version to deploy
  The instructions below assume you use release 0.3.12.
    - Add `export CHARTS_VERSION=0.3.12` to `cluster/deployment/scratchneta/.envrc.vars`
    - Add `export OVERRIDE_VERSION=0.3.12` to `cluster/deployment/scratchneta/.envrc.vars`
    - Run `cncluster update_config active 0 0.3.12` (this will update `cluster/deployment/scratchneta/config.yaml`)
    - See also section [Versions and Repositories](#versions-and-repositories).
- Configure the number of SVs in the cluster.
    - Add `export DSO_SIZE=1` to `cluster/deployment/scratchneta/.envrc.vars` to use a network with 1 SV
- Deploy the cluster.
    - Run `CNCLUSTER_SKIP_DOCKER_CHECK=1 cncluster apply`
    - Use `CNCLUSTER_SKIP_DOCKER_CHECK` in case cncluster complains that your local version is not published, even though we don't use it.
- Update the config to add staging nodes
    - Run `cncluster update_config upgrade 1 0.3.12` (this will update `cluster/deployment/scratchneta/config.yaml`)
- Deploy the staging nodes
    - Run `CNCLUSTER_SKIP_DOCKER_CHECK=1 cncluster apply`
    - This only works if the version does not change, otherwise `cncluster apply` will try to tweak the legacy nodes, and you will run into compatibility issue.
      If you need to change the version together with the HDM, use individual `cncluster pulumi <stack> up` commands.
- Trigger the HDM vote
    - Run `cncluster vote_for_migration 1`
- Wait for domain dumps to be written
    - Logs should contain "Wrote domain migration dump" [entries](https://console.cloud.google.com/logs/query;query=resource.labels.cluster_name%3D%22cn-scratchanet%22%0A%22Wrote%20domain%20migration%20dump%22;cursorTimestamp=2025-02-26T13:14:08.215863743Z;duration=PT30M?project=da-cn-scratchnet&inv=1&invt=Abqmvg)
- Wait for apps to catch up
    - Logs should not contain any recent "Ingested Transaction" [entries](https://console.cloud.google.com/logs/query;query=resource.labels.cluster_name%3D%22cn-scratchanet%22%0A%22Ingested%20Transaction%22;duration=PT30M?project=da-cn-scratchnet&inv=1&invt=Abqmvg)
- Configure the network to switch to the new migration
    - Run `cncluster update_config_to_migrate` and inspect `cluster/deployment/scratchneta/config.yaml` to see what it did
- Execute the migration
    - Run `SPLICE_MIGRATION_ID=1 SPLICE_SV=? cncluster pulumi sv-canton up` for every deployed SV, using values `sv-1, sv-2, ...` for `?`.
    - Run `cncluster pulumi canton-network up`
    - Run `cncluster pulumi validator1 up`
- Verify that we are running the new migration
    - Open https://scan.sv-2.scratcha.network.canton.global/dso, check `migrationId` fields
- Complete the migration
    - Edit `cluster/deployment/scratchneta/config.yaml`, removing the `migratingFrom` entry from the active migration
- Add a new SV
    - Run `cncluster apply_sv`
- Verify that the new SV is part of the DSO
    - Open https://scan.sv-2.scratcha.network.canton.global/dso, search for `"svs"`
- Verify that the new SV has completed long-running background processes
    - Look for "This history won't need any further backfilling" [log entries](https://console.cloud.google.com/logs/query;query=resource.labels.cluster_name%3D%22cn-scratchanet%22%0A%22This%20history%20won't%20need%20any%20further%20backfilling%22%0Aresource.labels.namespace_name%3D%22sv%22;cursorTimestamp=2025-02-26T14:31:47.457160517Z;duration=PT1H?project=da-cn-scratchnet&inv=1&invt=Abqmvg).
- Configure the network to use the local release
    - Undo version changes in `cluster/deployment/scratchneta/.envrc.vars`:
        - Modify `CHART_VERSION` to `CHART_VERSION=local`
    - In `cluster/deployment/scratchneta/config.yaml`, remove the version key from the active synchronizerMigration
- Publish a local release from your local code
    - Check out the version you want to deploy.
        - Run `git checkout my-branch`
        - Do not use this for release line branches, those are published from CI
    - Build and publish docker images
        - This step must be run after changing CHART_VERSION
        - Run `make -C $SPLICE_ROOT clean`
        - Run `make -C $SPLICE_ROOT build`
        - Run `make -C $SPLICE_ROOT docker-push -j`
- Upgrade nodes as required
    - Run `cncluster apply_sv`
    - Run `SPLICE_MIGRATION_ID=1 SPLICE_SV=? cncluster pulumi sv-canton up` for every deployed SV, using values `sv-1, sv-2, ...` for `?`.
    - Run `cncluster pulumi canton-network up`
    - Run `cncluster pulumi validator1 up`

## Deploying with KMS

KMS support (for SVs, validators) is actively being worked on, so expect changes.

Currently we support:

- Deploying `validator1` with participant KMS. You need to set `validator1.kms` in the deployment directory `config.yaml`.
- Deploying an SV with participant KMS. You need to set `svs.sv-X.participant.kms` in the deployment directory `config.yaml` (`sv-X` being your target SV; `sv` also works).

In both cases, the format for `kms` is:

```
kms:
  type: gcp // the default and only supported value here at the moment
  locationId: us-central1 // or whatever matches your keyring
  projectId: // defaults to current cluster's project
  keyRingId: // you must set this to a keyring that already exists
```

We don't create GCP keyrings automatically; if your desired keyring doesn't exist you'll need to create it manually, for example [through the UI](https://console.cloud.google.com/security/kms/keyrings).
Pick a single-region keyring that matches the region of your deployment.
[GCP keyrings cannot be deleted](https://www.pulumi.com/registry/packages/gcp/api-docs/kms/keyring/), so create new keyrings carefully.

Independently of the keyring: Each KMS key costs us $1.00-$2.50 per month just for existing ([GCP pricing](https://cloud.google.com/kms/pricing).
A fresh participants registers 3 keys.
We have no automatic logic for cleaning up unused keys (yet); until further notice it's the responsibility of whoever registered those keys to make sure that they are destroyed eventually.

The more general gotcha around migrating (a Canton node) to using KMS also applies: [you can't](https://dev.network.canton.global/validator_operator/validator_security.html#migrating-an-existing-validator-to-use-an-external-kms).
For non-MainNet deployments it's recommended to just `down` the existing deployment, including all databases, and then redeploy (and reonboard) with the fresh KMS-enabled deployment.

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
