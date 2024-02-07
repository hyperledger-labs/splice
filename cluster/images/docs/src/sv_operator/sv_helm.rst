.. _sv-helm:

Kubernetes-Based Deployment of a Super Validator node
=====================================================

This section describes deploying a Super Validator (SV) node in kubernetes using Helm
charts.  The Helm charts deploy a complete node and connect it to a
target cluster.

Digital Asset currently operates two Canton Network clusters: `TestNet` and `DevNet`. Both upgrade weekly. `DevNet` is usually on the latest release. `TestNet` follows it on a one-week delay, and has some `DevNet`-only features disabled. Please use `DevNet` unless you have a specific reason not to.

Note that there is not yet data retention from release to release in either
`DevNet` or `TestNet`. This will change as the models and API's
stabilize, but for now, each release should be a full release from
scratch. No data should be retained when an environment is updated.

Requirements
------------

1) Access to the following two Artifactory repositories:

    a. `Canton Network Docker repository <https://digitalasset.jfrog.io/ui/native/canton-network-docker>`_
    b. `Canton Network Helm repository <https://digitalasset.jfrog.io/ui/native/canton-network-helm/>`_

2) A running Kubernetes cluster in which you have administrator access to create and manage namespaces.
3) A development workstation with the following:

    a. ``kubectl`` - At least v1.26.1
    b. ``helm`` - At least v3.11.1

4) Your cluster either needs to be connected to the GCP DA Canton
   VPN or you need a static egress IP. In the latter case,
   please provide that IP address to your contact at Digital Asset to
   add it to the firewall rules.

5) Please download the release artifacts containing the sample Helm value files, from here: |bundle_download_link|, and extract the bundle:

.. parsed-literal::

  tar xzvf |version|\_cn-node-0.1.0-SNAPSHOT.tar.gz

.. _sv-identity:

Generating an SV identity
-------------------------

SV operators are identified by a human-readable name and an EC public key.
This identification is stable across deployments of the Canton network.
You are, for example, expected to reuse your SV name and public key between (test-)network resets.

Use the following shell commands to generate a keypair in the format expected by the SV node software: ::

  # Generate the keypair
  openssl ecparam -name prime256v1 -genkey -noout -out sv-keys.pem

  # Encode the keys
  public_key_base64=$(openssl ec -in sv-keys.pem -pubout -outform DER 2>/dev/null | base64 | tr -d "\n")
  private_key_base64=$(openssl pkcs8 -topk8 -nocrypt -in sv-keys.pem -outform DER 2>/dev/null | base64 | tr -d "\n")

  # Output the keys
  echo "public-key = \"$public_key_base64\""
  echo "private-key = \"$private_key_base64\""

  # Clean up
  rm sv-keys.pem

..
  Based on `scripts/generate-sv-keys.sh`

These commands should result in an output similar to ::

  public-key = "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE1eb+JkH2QFRCZedO/P5cq5d2+yfdwP+jE+9w3cT6BqfHxCd/PyA0mmWMePovShmf97HlUajFuN05kZgxvjcPQw=="
  private-key = "MEECAQAwEwYHKoZIzj0CAQYIKoZIzj0DAQcEJzAlAgEBBCBsFuFa7Eumkdg4dcf/vxIXgAje2ULVz+qTKP3s/tHqKw=="

Store both keys in a safe location.
You will be using them every time you want to deploy a new SV node, i.e., also when deploying an SV node to a different deployment of the Canton Network and for redeploying an SV node after a (test-)network reset.

The `public-key` and your desired *SV name* need to be approved by a threshold of currently active SVs in order for you to be able to join the network as an SV.
For `DevNet` and the current early version of `TestNet`, send the `public-key` and your desired SV name to your point of contact at Digital Asset (DA) and wait for confirmation that your SV identity has been approved and configured at existing SV nodes.

.. _identity-token:

Preparing a Cluster for Installation
------------------------------------

In the following, you will need your Artifactory credentials from
https://digitalasset.jfrog.io/ui/user_profile. Based on that, set the following environment variables.

====================== ==========================================================================================
Name                   Value
---------------------- ------------------------------------------------------------------------------------------
ARTIFACTORY_USER       Your Artifactory user name shown at the top right.
ARTIFACTORY_PASSWORD   Your Artifactory Identity token. If you don't have one you can generate one on your profile page.
====================== ==========================================================================================

Ensure that your local helm installation has access to the Digital Asset Helm chart repository:

.. code-block:: bash

    helm repo add canton-network-helm \
        https://digitalasset.jfrog.io/artifactory/api/helm/canton-network-helm \
        --username ${ARTIFACTORY_USER} \
        --password ${ARTIFACTORY_PASSWORD}

Create the application namespace within Kubernetes and ensure it has image pull credentials for fetching images from the Digital Asset Artifactory repository used for Docker images.

.. code-block:: bash

    kubectl create ns sv

    kubectl create secret docker-registry docker-reg-cred \
        --docker-server=digitalasset-canton-network-docker.jfrog.io \
        --docker-username=${ARTIFACTORY_USER} \
        --docker-password=${ARTIFACTORY_PASSWORD} \
        -n sv

    kubectl patch serviceaccount default -n sv \
        -p '{"imagePullSecrets": [{"name": "docker-reg-cred"}]}'

.. _helm-sv-auth:

Configuring Authentication
--------------------------

For security, the various components that comprise your SV node need to be able to authenticate themselves to each other,
as well as be able to authenticate external UI and API users.
We use JWT access tokens for authentication and expect these tokens to be issued by an (external) `OpenID Connect <https://openid.net/connect/>`_ (OIDC) provider.
You must:

1. Set up an OIDC provider in such a way that both backends and web UI users are able to obtain JWTs in a supported form.

2. Configure your backends to use that OIDC provider.

.. _helm-sv-auth-requirements:

OIDC Provider Requirements
++++++++++++++++++++++++++

This section provides pointers for setting up an OIDC provider for use with your SV node.
Feel free to skip directly to :ref:`helm-sv-auth0` if you plan to use `Auth0 <https://auth0.com>`_ for your SV node's authentication needs.
That said, we encourage you to move to an OIDC provider different from Auth0 for the long-term production deployment of your SV,
to avoid security risks resulting from a majority of SVs depending on the same authentication provider
(which could expose the whole network to potential security problems at this provider).

Your OIDC provider must be reachable [#reach]_ at a well known (HTTPS) URL.
In the following, we will refer to this URL as ``OIDC_AUTHORITY_URL``.
Both your SV node and any users that wish to authenticate to a web UI connected to your SV node must be able to reach the ``OIDC_AUTHORITY_URL``.
We require your OIDC provider to provide a `discovery document <https://openid.net/specs/openid-connect-discovery-1_0.html>`_ at ``OIDC_AUTHORITY_URL/.well-known/openid-configuration``.
We furthermore require that your OIDC provider exposes a `JWK Set <https://datatracker.ietf.org/doc/html/rfc7517>`_ document.
In this documentation, we assume that this document is available at ``OIDC_AUTHORITY_URL/.well-known/jwks.json``.

For machine-to-machine (SV node component to SV node component) authentication,
your OIDC provider must support the `OAuth 2.0 Client Credentials Grant <https://tools.ietf.org/html/rfc6749#section-4.4>`_ flow.
This means that you must be able to configure (`CLIENT_ID`, `CLIENT_SECRET`) pairs for all SV node components that need to authenticate to others.
Currently, these are the validator app backend and the SV app backend - both need to authenticate to the SV node's Canton participant.
The `sub` field of JWTs issued through this flow must match the user ID configured as `ledger-api-user` in :ref:`helm-sv-auth-secrets-config`.
In this documentation, we assume that the `sub` field of these JWTs is formed as ``CLIENT_ID@clients``.
If this is not true for your OIDC provider, pay extra attention when configuring ``ledger-api-user`` values below.

For user-facing authentication - allowing users to access the various web UIs hosted on your SV node,
your OIDC provider must support the `OAuth 2.0 Authorization Code Grant <https://datatracker.ietf.org/doc/html/rfc6749#section-4.1>`_ flow
and allow you to obtain client identifiers for the web UIs your SV node will be hosting.
Currently, these are the SV web UI, the Wallet web UI and the CNS web UI.
You might be required to whitelist a range of URLs on your OIDC provider, such as "Allowed Callback URLs", "Allowed Logout URLs", "Allowed Web Origins", and "Allowed Origins (CORS)".
If you are using the ingress configuration of this runbook, the correct URLs to configure here are
``https://sv.sv.svc.YOUR_HOSTNAME`` (for the SV web UI) ,
``https://wallet.sv.svc.YOUR_HOSTNAME`` (for the Wallet web UI) and
``https://cns.sv.svc.YOUR_HOSTNAME`` (for the CNS web UI).
An identifier that is unique to the user must be set via the `sub` field of the issued JWT.
On some occasions, this identifier will be used as a user name for that user on your SV node's Canton participant.
In :ref:`helm-sv-install`, you will be required to configure a user identifier as the ``validatorWalletUser`` -
make sure that whatever you configure there matches the contents of the `sub` field of JWTs issued for that user.

*All* JWTs issued for use with your SV node:

- must be signed using the RS256 signing algorithm

In the future, your OIDC provider might additionally be required to issue JWTs with a ``scope`` explicitly set to ``daml_ledger_api``
(when requested to do so as part of the OAuth 2.0 authorization code flow).

Summing up, your OIDC provider setup must provide you with the following configuration values:

======================= ===========================================================================
Name                    Value
----------------------- ---------------------------------------------------------------------------
OIDC_AUTHORITY_URL      The URL of your OIDC provider for obtaining the ``openid-configuration`` and ``jwks.json``.
VALIDATOR_CLIENT_ID     The client id of the Auth0 app for the validator app backend
VALIDATOR_CLIENT_SECRET The client secret of the Auth0 app for the validator app backend
SV_CLIENT_ID            The client id of the Auth0 app for the SV app backend
SV_CLIENT_SECRET        The client secret of the Auth0 app for the SV app backend
WALLET_UI_CLIENT_ID     The client id of the Auth0 app for the wallet UI.
SV_UI_CLIENT_ID         The client id of the Auth0 app for the SV UI.
CNS_UI_CLIENT_ID        The client id of the Auth0 app for the CNS UI.
======================= ===========================================================================

We are going to use these values, exported to environment variables named as per the `Name` column, in :ref:`helm-sv-auth-secrets-config` and :ref:`helm-sv-install`.

By default, all audience values are configured to be ``https://canton.network.global``.
Once you can confirm that your setup is working correctly using this (simple) default,
we recommend that you configure dedicated audience values that match your deployment and URLs.
You can configure audiences of your choice for the participant ledger API, the validator backend API, and the SV backend API.
We will refer to these using the following configuration values:

==================================== ===========================================================================
Name                                 Value
------------------------------------ ---------------------------------------------------------------------------
OIDC_AUTHORITY_LEDGER_API_AUDIENCE   The audience for the participant ledger API. e.g. ``https://ledger_api.example.com``
OIDC_AUTHORITY_VALIDATOR_AUDIENCE    The audience for the validator backend API. e.g. ``https://validator.example.com/api``
OIDC_AUTHORITY_SV_AUDIENCE           The audience for the SV backend API. e.g. ``https://sv.example.com/api``
==================================== ===========================================================================

In case you are facing trouble with setting up your (non-Auth0) OIDC provider,
it can be beneficial to skim the instructions in :ref:`helm-sv-auth0` as well, to check for functionality or configuration details that your OIDC provider setup might be missing.

.. [#reach] The URL must be reachable from the Canton participant, validator app and SV app running in your cluster, as well as from all web browsers that should be able to interact with the SV and wallet UIs.

    .. TODO(#2052) use a unique audience for each app

.. _helm-sv-auth0:

Configuring an Auth0 Tenant
+++++++++++++++++++++++++++

To configure `Auth0 <https://auth0.com>`_ as your SV's OIDC provider, perform the following:

1. Create an Auth0 tenant for your SV
2. Create an Auth0 API that controls access to the ledger API:

    a. Navigate to Applications > APIs and click "Create API". Set name to ``Daml Ledger API``,
       set identifier to ``https://canton.network.global``.
       Alternatively, if you would like to configure your own audience, you can set the identifier here. e.g. ``https://ledger_api.example.com``.
    b. Under the Permissions tab in the new API, add a permission with scope ``daml_ledger_api``, and a description of your choice.
    c. On the Settings tab, scroll down to "Access Settings" and enable "Allow Offline Access", for automatic token refreshing.

3. Create an Auth0 Application for the validator backend:

    a. In Auth0, navigate to Applications -> Applications, and click the "Create Application" button.
    b. Name it ``Validator app backend``, choose "Machine to Machine Applications", and click Create.
    c. Choose the ``Daml Ledger API`` API you created in step 2 in the "Authorize Machine to Machine Application" dialog and click Authorize.

4. Create an Auth0 Application for the SV backend.
   Repeat all steps described in step 3, using ``SV app backend`` as the name of your application.

5. Create an Auth0 Application for the SV web UI:

    a. In Auth0, navigate to Applications -> Applications, and click the "Create Application" button.
    b. Choose "Single Page Web Applications", call it ``SV web UI``, and click Create.
    c. Determine the URL for your validator's SV UI.
       If you're using the ingress configuration of this runbook, that would be ``https://sv.sv.svc.YOUR_HOSTNAME``.
    d. In the Auth0 application settings, add the SV URL to the following:

       - "Allowed Callback URLs"
       - "Allowed Logout URLs"
       - "Allowed Web Origins"
       - "Allowed Origins (CORS)"
    e. Save your application settings.

6. Create an Auth0 Application for the wallet web UI.
   Repeat all steps described in step 5, with following modifications:

   - In step b, use ``Wallet web UI`` as the name of your application.
   - In steps c and d, use the URL for your SV's *wallet* UI.
     If you're using the ingress configuration of this runbook, that would be ``https://wallet.sv.svc.YOUR_HOSTNAME``.

7. Create an Auth0 Application for the CNS web UI.
   Repeat all steps described in step 5, with following modifications:

   - In step b, use ``CNS web UI`` as the name of your application.
   - In steps c and d, use the URL for your SV's *CNS* UI.
     If you're using the ingress configuration of this runbook, that would be ``https://cns.sv.svc.YOUR_HOSTNAME``.

8. (Optional) Similarly to the ledger API above, the default audience is set to ``https://canton.network.global``.
    If you want to configure a different audience to your APIs, you can do so by creating new Auth0 APIs with an identifier set to the audience of your choice. For example,

    a. Navigate to Applications > APIs and click "Create API". Set name to ``SV App API``,
       set identifier for the SV backend app API e.g. ``https://sv.example.com/api``.
    b. Create another API by setting name to ``Validator App API``,
       set identifier for the Validator backend app e.g. ``https://validator.example.com/api``.

Please refer to Auth0's `own documentation on user management <https://auth0.com/docs/manage-users>`_ for pointers on how to set up end-user accounts for the two web UI applications you created.
Note that you will need to create at least one such user account for completing the steps in :ref:`helm-sv-install` - for being able to log in as your SV node's administrator.
You will be asked to obtain the user identifier for this user account.
It can be found in the Auth0 interface under User Management -> Users -> your user's name -> user_id (a field right under the user's name at the top).

We will use the environment variables listed in the table below to refer to aspects of your Auth0 configuration:

================================== ===========================================================================
Name                               Value
---------------------------------- ---------------------------------------------------------------------------
OIDC_AUTHORITY_URL                 ``https://AUTH0_TENANT_NAME.us.auth0.com``
OIDC_AUTHORITY_LEDGER_API_AUDIENCE The optional audience of your choice for Ledger API. e.g. ``https://ledger_api.example.com``
VALIDATOR_CLIENT_ID                The client id of the Auth0 app for the validator app backend
VALIDATOR_CLIENT_SECRET            The client secret of the Auth0 app for the validator app backend
SV_CLIENT_ID                       The client id of the Auth0 app for the SV app backend
SV_CLIENT_SECRET                   The client secret of the Auth0 app for the SV app backend
WALLET_UI_CLIENT_ID                The client id of the Auth0 app for the wallet UI.
SV_UI_CLIENT_ID                    The client id of the Auth0 app for the SV UI.
CNS_UI_CLIENT_ID                   The client id of the Auth0 app for the CNS UI.
================================== ===========================================================================

The ``AUTH0_TENANT_NAME`` is the name of your Auth0 tenant as shown at the top left of your Auth0 project.
You can obtain the client ID and secret of each Auth0 app from the settings pages of that app.

.. _helm-sv-auth-secrets-config:

Configuring Authentication on your SV Node
++++++++++++++++++++++++++++++++++++++++++

We are now going to configure your SV node software based on the OIDC provider configuration values your exported to environment variables at the end of either :ref:`helm-sv-auth-requirements` or :ref:`helm-sv-auth0`.
(Note that some authentication-related configuration steps are also included in :ref:`helm-sv-install`.)

The following kubernetes secret will instruct the participant to create a service user for your SV app:

.. code-block:: bash

    kubectl create --namespace sv secret generic cn-app-sv-ledger-api-auth \
        "--from-literal=ledger-api-user=${SV_CLIENT_ID}@clients" \
        "--from-literal=url=${OIDC_AUTHORITY_URL}/.well-known/openid-configuration" \
        "--from-literal=client-id=${SV_CLIENT_ID}" \
        "--from-literal=client-secret=${SV_CLIENT_SECRET}"
        # Optional. uncomment it if you want to set audience for ledger API
        # "--from-literal=audience=${OIDC_AUTHORITY_LEDGER_API_AUDIENCE}"

The validator app backend requires the following secret.

.. code-block:: bash

    kubectl create --namespace sv secret generic cn-app-validator-ledger-api-auth \
        "--from-literal=ledger-api-user=${VALIDATOR_CLIENT_ID}@clients" \
        "--from-literal=url=${OIDC_AUTHORITY_URL}/.well-known/openid-configuration" \
        "--from-literal=client-id=${VALIDATOR_CLIENT_ID}" \
        "--from-literal=client-secret=${VALIDATOR_CLIENT_SECRET}"
        # Optional. uncomment it if you want to set audience for ledger API
        # "--from-literal=audience=${OIDC_AUTHORITY_LEDGER_API_AUDIENCE}"

To setup the wallet and SV UI, create the following two secrets.

.. code-block:: bash

    kubectl create --namespace sv secret generic cn-app-wallet-ui-auth \
        "--from-literal=url=${OIDC_AUTHORITY_URL}" \
        "--from-literal=client-id=${WALLET_UI_CLIENT_ID}"

    kubectl create --namespace sv secret generic cn-app-sv-ui-auth \
        "--from-literal=url=${OIDC_AUTHORITY_URL}" \
        "--from-literal=client-id=${SV_UI_CLIENT_ID}"

    kubectl create --namespace sv secret generic cn-app-cns-ui-auth \
        "--from-literal=url=${OIDC_AUTHORITY_URL}" \
        "--from-literal=client-id=${CNS_UI_CLIENT_ID}"

Configuring your CometBft node
------------------------------

Every SV node also deploys a CometBft node. This node must be configured to join the existing Canton network BFT chain.
To do that, you first must generate the keys that will identify the node.

.. note::
  You need access to the canton `network docker repo <https://digitalasset.jfrog.io/ui/native/canton-network-docker>`_ to successfully generate the node identity.
  You can access your username and get the Identity token for your Artifactory account through the UI, using the top right `Edit profile` option.

  | This can be configured by running:
  | :code:`docker login -u <your_artifactory_user> -p <your_artifactory_api_key> digitalasset-canton-network-docker.jfrog.io`

.. _cometbft-identity:

Generating your CometBft node keys
++++++++++++++++++++++++++++++++++
To generate the node config you must have access to the CometBft docker image provided through the Canton Network artifacotry (digitalasset-canton-network-docker.jfrog.io).

Use the following shell commands to generate the proper keys:

.. parsed-literal::

  # Create a folder to store the config
  mkdir cometbft
  cd cometbft
  # Init the node
  docker run --rm -v $(pwd):/init digitalasset-canton-network-docker.jfrog.io/digitalasset/cometbft:|version| init --home /init
  # Read the node id and keep a note of it for the deployment
  docker run --rm -v $(pwd):/init digitalasset-canton-network-docker.jfrog.io/digitalasset/cometbft:|version| show-node-id --home /init

Please keep a note of the node ID printed out above.

In addition, please retain some of the configuration files generated, as follows (you might need to change the permissions/ownership for them as they are accessible only by the root user): ::

  cometbft/config/node_key.json
  cometbft/config/priv_validator_key.json

Any other files can be ignored.

.. _helm-cometbft-secrets-config:

Configuring your CometBft node keys
+++++++++++++++++++++++++++++++++++

The CometBft node is configured with a secret, based on the output from :ref:`Generating the CometBft node identity <cometbft-identity>`
The secret is created as follows, with the `node_key.json` and `priv_validator_key.json` files representing the files generated as part of the node identity:

.. code-block:: bash

    kubectl create --namespace sv secret generic cometbft-keys \
        "--from-file=node_key.json=node_key.json" \
        "--from-file=priv_validator_key.json=priv_validator_key.json"

.. _helm-cometbft-state-sync:

Configuring CometBft state sync
+++++++++++++++++++++++++++++++

CometBft has a feature called state sync that allows a new peer to catch up quickly by reading a snapshot of data at or near the head of
the chain and verifying it instead of fetching and replaying every block. (See `CometBft documentation <https://docs.cometbft.com/v0.34/core/state-sync>`_).
This leads to drastically shorter times to onboard new nodes at the cost of new nodes having a truncated block history.
Further, when the chain has been pruned, state sync needs to be enabled on new nodes in order to bootstrap them successfully.

There are 3 main configuration parameters that control state sync in CometBft:

- `rpc_servers` - The list of CometBft RPC servers to connect to in order to fetch snapshots
- `trust_height` - Height at which you should trust the chain
- `trust_hash` - Hash corresponding to the trusted height

A CometBft node installed using our helm charts (see :ref:`helm-sv-install`) with the default values set in
``cn-node-0.1.0-SNAPSHOT/examples/sv-helm/cometbft-values.yaml`` automatically uses state sync for bootstrapping
if:

- it has not been explicitly disabled by setting `stateSync.enable` to `false`
- the block chain is mature enough for at least 1 state snapshot to have been taken i.e.
  the height of the latest block is greater than or equal to the configured interval between snapshots

The snapshots are fetched from the founding SV node which exposes its CometBft RPC API at `https://sv.sv-1.svc.TARGET_CLUSTER.network.canton.global:443/cometbft-rpc/`.
This can be changed by setting `stateSync.rpcServers` accordingly. The `trust_height` and `trust_hash` are computed dynamically via an initialization script
and setting them explicitly should not be required and is not currently supported.

.. _helm-sv-postgres:

Installing Postgres instances
-----------------------------

The SV node requires 4 Postgres instances: one for the sequencer, one for the mediator,
one for the participant, and one for the CN apps. While they can all use the same instance,
we recommend splitting them up into 4 separate instances for better operational flexibility,
and also for better control over backup processes.

We support both Cloud-hosted Postgres instances and Postgres instances running in the cluster.

Creating k8s Secrets for Postgres Passwords
+++++++++++++++++++++++++++++++++++++++++++

All apps support reading the Postgres password from a Kubernetes secret.
Currently, all apps use the Postgres user ``cnadmin``.
The password can be setup with the following command, assuming you set the environment variables ``POSTGRES_PASSWORD_XXX`` to secure values:

.. code-block:: bash

    kubectl create secret generic sequencer-pg-secret \
        --from-literal=postgresPassword=${POSTGRES_PASSWORD_SEQUENCER} \
        -n sv
    kubectl create secret generic mediator-pg-secret \
        --from-literal=postgresPassword=${POSTGRES_PASSWORD_MEDIATOR} \
        -n sv
    kubectl create secret generic participant-pg-secret \
        --from-literal=postgresPassword=${POSTGRES_PASSWORD_PARTICIPANT} \
        -n sv
    kubectl create secret generic apps-pg-secret \
        --from-literal=postgresPassword=${POSTGRES_PASSWORD_APPS} \
        -n sv


Postgres in the Cluster
+++++++++++++++++++++++

If you wish to run the Postgres instances as pods in your cluster, you can use the `cn-postgres` Helm chart to install them:

.. code-block:: bash

    helm install sequencer-pg canton-network-helm/cn-postgres -n sv --version ${CHART_VERSION} -f cn-node-0.1.0-SNAPSHOT/examples/sv-helm/postgres-values-sequencer.yaml --wait
    helm install mediator-pg canton-network-helm/cn-postgres -n sv --version ${CHART_VERSION} -f cn-node-0.1.0-SNAPSHOT/examples/sv-helm/postgres-values-mediator.yaml --wait
    helm install participant-pg canton-network-helm/cn-postgres -n sv --version ${CHART_VERSION} -f cn-node-0.1.0-SNAPSHOT/examples/sv-helm/postgres-values-participant.yaml --wait
    helm install apps-pg canton-network-helm/cn-postgres -n sv --version ${CHART_VERSION} -f cn-node-0.1.0-SNAPSHOT/examples/sv-helm/postgres-values-apps.yaml --wait


Cloud-Hosted Postgres
+++++++++++++++++++++

If you wish to use cloud-hosted Postgres instances, please configure and initialize each of them as follows:

- Use Postgres version 14
- Create a database called ``cantonnet``
- Create a user called ``cnadmin`` with the password as configured in the kubernetes secrets above

Note that the default Helm values files used below assume that the Postgres instances are deployed using the Helm charts above,
thus are accessible at hostname sequencer-pg, mediator-pg, etc. If you are using cloud-hosted Postgres instances,
please override the hostnames under `persistence.host` with the IP addresses of the Postgres instances.

Storage Backups
---------------

Backup of Postgres Instances
++++++++++++++++++++++++++++

Please make sure your Postgres instances are backed up at least every 4 hours. We will provide guidelines on retention of older backups
at a later point in time.

While most backups can be taken independently, there is one strict ordering requirement between them:
The backup of the apps postgres instance must be taken at a point in time strictly earlier than that of the participant.
Please make sure the apps instance backup is completed before starting the participant one.

If you are running your own Postgres instances in the cluster, backups can be taken either using tools like `pgdump`, or through snapshots of the underlying Persistent Volume.
Similarly, if you are using Cloud-hosted Postgres, you can either use tools like `pgdump` or backup tools provided by the Cloud provider.

Backup of CometBFT
++++++++++++++++++

In addition to the Postgres instances, the storage used by CometBFT should also be backed up every 4 hours.
Since CometBFT does not use Postgres, the only reliable way to backup its storage is through snapshots of the underlying Persistent Volume.

.. _helm-sv-install:

Installing the Software
-----------------------

Configuring the Helm Charts
+++++++++++++++++++++++++++

To install the Helm charts needed to start an SV node connected to the
cluster, you will need to meet a few preconditions. The first is that
there needs to be an environment variable defined to refer to the
version of the Helm charts necessary to connect to this environment:

Note that because of the lack of data retention from release to release
in either `DevNet` or `TestNet`, every release should be installed
from scratch into a completely clean environment. Data should not be
retained, and there is no current support for helm chart upgrades or
similar.

|chart_version_set|

An SV node includes a CometBft node so you also need to configure
that. Please modify the file ``cn-node-0.1.0-SNAPSHOT/examples/sv-helm/cometbft-values.yaml`` as follows:

- Replace all instances of ``TARGET_CLUSTER`` with |cn_cluster|, per the cluster to which you are connecting.
- Replace ``YOUR_SV_NAME`` with the name you chose when creating the SV identity (this must be an exact match of the string for your SV to be approved to onboard)
- Replace ``YOUR_COMETBFT_NODE_ID`` with the id obtained when generating the config for the CometBft node
- Replace ``YOUR_HOSTNAME`` with the hostname that will be used for the ingress
- Add `db.volumeSize` and `db.volumeStorageClass` to the values file adjust persistent storage size and storage class if necessary. (These values default to 20GiB and `standard-rwo`)

.. _helm-configure-global-domain:

Please modify the file ``cn-node-0.1.0-SNAPSHOT/examples/sv-helm/participant-values.yaml`` as follows:

- Replace ``TARGET_CLUSTER`` in the `globalDomain.url` entry with |cn_cluster|, per the cluster to which you are connecting.
- If you want to configure the audience for the participant, replace ``OIDC_AUTHORITY_LEDGER_API_AUDIENCE`` in the `auth.targetAudience` entry with audience for the ledger API. e.g. ``https://ledger_api.example.com``.
- Update the `auth.jwksUrl` entry to point to your auth provider's JWK set document by replacing ``OIDC_AUTHORITY_URL`` with your auth provider's OIDC URL, as explained above.
- If you are running on a version of Kubernetes earlier than 1.24, set `enableHealthProbes` to `false` to disable the gRPC liveness and readiness probes.
- Add `db.volumeSize` and `db.volumeStorageClass` to the values file adjust persistant storage size and storage class if necessary. (These values default to 20GiB and `standard-rwo`)

Please modify the file ``cn-node-0.1.0-SNAPSHOT/examples/sv-helm/scan-values.yaml`` as follows:

- Replace all instances of ``TARGET_CLUSTER`` with |cn_cluster|, per the cluster to which you are connecting.

An SV node includes a validator app so you also need to configure
that. Please modify the file ``cn-node-0.1.0-SNAPSHOT/examples/sv-helm/validator-values.yaml`` as follows:

- Replace all instances of ``TARGET_CLUSTER`` with |cn_cluster|, per the cluster to which you are connecting.
- If you want to configure the audience for the Validator app backend API, replace ``OIDC_AUTHORITY_VALIDATOR_AUDIENCE`` in the `auth.audience` entry with audience for the Validator app backend API. e.g. ``https://validator.example.com/api``.
- If you want to configure the audience for the Ledger API, replace ``OIDC_AUTHORITY_LEDGER_API_AUDIENCE`` in the `auth.ledgerApiAudience` entry with audience for the Ledger API. e.g. ``https://ledger_api.example.com``.
- Replace ``OPERATOR_WALLET_USER_ID`` with the user ID in your IAM that you want to use to log into the wallet as the SV party. Note that this should be the full user id, e.g., ``auth0|43b68e1e4978b000cefba352``, *not* only the suffix ``43b68e1e4978b000cefba352``
- Update the `auth.jwksUrl` entry to point to your auth provider's JWK set document by replacing ``OIDC_AUTHORITY_URL`` with your auth provider's OIDC URL, as explained above.

The private and public key for your SV are defined in a K8s secret.
If you haven't done so yet, please first follow the instructions in
the :ref:`Generating an SV Identity<sv-identity>` section to obtain
and register a name and keypair for your SV. Replace
``YOUR_PUBLIC_KEY`` and ``YOUR_PRIVATE_KEY`` with the ``public-key``
and ``private-key`` values obtained as part of generating your SV
identity.

.. code-block:: bash

    kubectl create secret --namespace sv generic cn-app-sv-key \
        --from-literal=public=YOUR_PUBLIC_KEY \
        --from-literal=private=YOUR_PRIVATE_KEY

For configuring your sv app, please modify the file ``cn-node-0.1.0-SNAPSHOT/examples/sv-helm/sv-values.yaml`` as follows:

- Replace all instances of ``TARGET_CLUSTER`` with |cn_cluster|, per the cluster to which you are connecting.
- If you want to configure the audience for the SV app backend API, replace ``OIDC_AUTHORITY_SV_AUDIENCE`` in the `auth.audience` entry with audience for the SV app backend API. e.g. ``https://sv.example.com/api``.
- Replace ``YOUR_SV_NAME`` with the name you chose when creating the SV identity (this must be an exact match of the string for your SV to be approved to onboard)
- Update the ``auth.jwksUrl`` entry to point to your auth provider's JWK set document by replacing ``OIDC_AUTHORITY_URL`` with your auth provider's OIDC URL, as explained above.
- Please set `domain.sequencerPublicUrl` to the URL to your sequencer service in the SV configuration. If you are using the ingress configuration of this runbook, you can just replace ``YOUR_HOSTNAME`` with your host name.
- Please set `scan.publicUrl` to the URL to your Scan app in the SV configuration. If you are using the ingress configuration of this runbook, you can just replace ``YOUR_HOSTNAME`` with your host name.

Your SV node will also be configured with a set of SV identities for your node to auto-approve as peer SVC members. The bundled artifacts consist of the lists of recommended values as follows:

- ``cn-node-0.1.0-SNAPSHOT/examples/sv-helm/approved-sv-id-values-test.yaml`` - the list of currently SVC-approved identities for `TestNet`
- ``cn-node-0.1.0-SNAPSHOT/examples/sv-helm/approved-sv-id-values-dev.yaml`` - the list of partners currently experimenting with onboarding SV nodes on `DevNet`. Note that this is a less strict list at the moment, and includes identities for e.g. Digital-Asset support employees.

Please identify the file out of the above corresponding to the network to which you are connecting, and after reviewing the file, set its path in an environment variable ``SV-IDENTITIES-FILE`` to be used below.

.. _sv-participant-identities-restore:

Restoring from an existing Particiant Identities Backup
+++++++++++++++++++++++++++++++++++++++++++++++++++++++

If you are restoring from an existing :ref:`participant identities backup <participant-identities-backup>`, you need to create another secret containing that dump. Here
we are assuming you've stored the dump in a file called ``participant-identities-dump.json`` in the current directory.

.. code-block:: bash

    kubectl create --namespace sv secret generic participant-identities-dump \
        "--from-file=content=participant-identities-dump.json"

You also need to configure the participant to not initialize automatically by uncommenting the following section in your ``participant-values.yaml``.

.. literalinclude:: ../../../../../apps/app/src/pack/examples/sv-helm/participant-values.yaml
    :start-after: PARTICIPANT_BOOTSTRAP_START
    :end-before: PARTICIPANT_BOOTSTRAP_END

Lastly, you need to configure the SV app to bootstrap the participant from the content of your secret by uncommenting the following section in your ``sv-values.yaml``.

.. literalinclude:: ../../../../../apps/app/src/pack/examples/sv-helm/sv-values.yaml
    :start-after: PARTICIPANT_BOOTSTRAP_START
    :end-before: PARTICIPANT_BOOTSTRAP_END

Note that restoring from a participant identities backup will only result in a functional participant if no participant with the same identity has ever been connected to the network (more specifically: to the global CN domain) since the network was last reset.
This implies that you can only restore from the same participant identities backup once per network deployment.
Also note that restoring from a participant identities backup is only possible if the participant is fresh and uninitialized, i.e., its database is completely empty.

.. _helm-install:

Installing the Helm Charts
++++++++++++++++++++++++++

With these files in place, you can execute the following helm commands
in sequence. It's generally a good idea to wait until each deployment
reaches a stable state prior to moving on to the next step.

.. code-block:: bash

    helm repo update
    helm install cometbft canton-network-helm/cn-cometbft -n sv --version ${CHART_VERSION} -f cn-node-0.1.0-SNAPSHOT/examples/sv-helm/cometbft-values.yaml --wait
    helm install global-domain canton-network-helm/cn-global-domain -n sv --version ${CHART_VERSION} -f cn-node-0.1.0-SNAPSHOT/examples/sv-helm/global-domain-values.yaml --wait
    helm install participant canton-network-helm/cn-participant -n sv --version ${CHART_VERSION} -f cn-node-0.1.0-SNAPSHOT/examples/sv-helm/participant-values.yaml --wait
    helm install sv canton-network-helm/cn-sv-node -n sv --version ${CHART_VERSION} -f cn-node-0.1.0-SNAPSHOT/examples/sv-helm/sv-values.yaml -f ${SV-IDENTITIES-FILE} --wait
    helm install validator canton-network-helm/cn-validator -n sv --version ${CHART_VERSION} -f cn-node-0.1.0-SNAPSHOT/examples/sv-helm/validator-values.yaml -f cn-node-0.1.0-SNAPSHOT/examples/sv-helm/sv-validator-values.yaml --wait
    helm install scan canton-network-helm/cn-scan -n sv --version ${CHART_VERSION} -f cn-node-0.1.0-SNAPSHOT/examples/sv-helm/scan-values.yaml --wait

Once this is running, you should be able to inspect the state of the
cluster and observe pods running in the new
namespace. A typical query might look as follows:

.. code-block:: bash

    $ kubectl get pods -n sv
    NAMESPACE         NAME                                                       READY   STATUS    RESTARTS      AGE
    sv                scan-app-5658d74b58-xvlmz                                  1/1     Running   0             14m
    sv                scan-web-ui-7db66d9f9d-kl9kw                               1/1     Running   0             14m
    sv                sv-app-7658c9fdd4-58xm6                                    1/1     Running   0             91m
    sv                sv-web-ui-84b6d7994c-w67rp                                 1/1     Running   0             91m
    sv                validator-app-b7fd68479-w4992                              1/1     Running   0             43m
    sv                wallet-web-ui-54c9ddbb8-nvkmp                              1/1     Running   0             43m
    sv                participant-6fdff7fc4-vzg8c                                3/3     Running   1 (72m ago)   72m
    sv                postgres-0                                                 1/1     Running   0             120m
    sv                cometbft-6fdff7fc4-vzg8c                                   1/1     Running   0             120m
    sv                global-domain-mediator-c57c9b55f                           1/1     Running   0             120m
    sv                global-domain-sequencer-c57c9b55f                          1/1     Running   0             120m


Note also that ``Pod`` restarts may happen during bringup,
particularly if all helm charts are deployed at the same time. The
``cn-sv-node`` cannot start until ``participant`` is running and
``participant`` cannot start until ``postgres`` is running.

.. _helm-sv-ingress:


Configuring the Cluster Ingress
-------------------------------

An IP whitelisting json file ``allowed-ip-ranges-external.json`` will be provided in each SV operations announcement.
This file contains other clusters' egress IPs that require access to your super validator's components. These IPs typically belong to peer super-validators, validators and the Digital Asset VPN.

Each SV member is required to configure their cluster ingress to allow traffic from these IPs to be operational.

* ``https://wallet.sv.svc.<YOUR_HOSTNAME>`` should be routed to service ``wallet-web-ui`` in the ``sv`` namespace.
* ``https://wallet.sv.svc.<YOUR_HOSTNAME>/api/validator`` should be routed to ``/api/validator`` at port 5003 of service ``validator-app`` in the ``sv`` namespace.
* ``https://sv.sv.svc.<YOUR_HOSTNAME>`` should be routed to service ``sv-web-ui`` in the ``sv`` namespace.
* ``https://sv.sv.svc.<YOUR_HOSTNAME>/api/sv`` should be routed to ``/api/sv`` at port 5014 of service ``sv-app`` in the ``sv`` namespace.
* ``https://scan.sv.svc.<YOUR_HOSTNAME>`` should be routed to service ``scan-web-ui`` in the ``sv`` namespace.
* ``https://scan.sv.svc.<YOUR_HOSTNAME>/api/scan`` should be routed to ``/api/scan`` at port 5012 in service ``scan-app`` in the ``sv`` namespace.
* ``cometbft.sv.svc.<YOUR_HOSTNAME>:26656`` should be routed to port 26656 of service ``cometbft-cometbft-p2p`` in the ``sv`` namespace using the TCP protocol.
  Please note that cometBFT traffic is purely TCP. TLS is not supported so SNI host routing for these traffic is not possible.
* ``https://cns.sv.svc.<YOUR_HOSTNAME>`` should be routed to service ``cns-web-ui`` in the ``sv`` namespace.
* ``https://cns.sv.svc.<YOUR_HOSTNAME>/api/validator`` should be routed to ``/api/validator`` at port 5003 of service ``validator-app`` in the ``sv`` namespace.
* ``https://sequencer.sv.svc.<YOUR_HOSTNAME>`` should be routed to port 5008 of service ``global-domain-sequencer`` in the ``sv`` namespace.

Internet ingress configuration is often specific to the network configuration and scenario of the
cluster being configured. To illustrate the basic requirements of an SV node ingress, we have
provided a Helm chart that configures the above cluster according to the routes above, as detailed in the sections below.

Your SV node should be configured with a url to your ``global-domain-sequencer`` so that other validators can subscribe to it.

Make sure your cluster's ingress is correctly configured for the sequencer service and can be accessed through the provided URL.
To check whether the sequencer is accessible, we can use the command below with the `grpcurl tool <https://github.com/fullstorydev/grpcurl>`_ :

.. code-block:: bash

    grpcurl <sequencer host>:<sequencer port> grpc.health.v1.Health/Check

If you are using the ingress configuration of this runbook, the ``<sequencer host>:<sequencer port>`` should be ``sequencer.sv.svc.YOUR_HOSTNAME:443``
Please replace ``YOUR_HOSTNAME`` with your host name.

If you see the response below, it means the sequencer is up and accessible through the URL.

.. code-block:: bash

    {
      "status": "SERVING"
    }


Requirements
++++++++++++

In order to install the reference charts, the following must be satisfied in your cluster:

* cert-manager must be available in the cluster (See `cert-manager documentation <https://cert-manager.io/docs/installation/helm/>`_)
* istio should be installed in the cluster (See `istio documentation <https://istio.io/latest/docs/setup/>`_)

**Example of Istio installation:**

.. code-block:: bash

    helm repo add istio https://istio-release.storage.googleapis.com/charts
    helm repo update
    helm install istio-base istio/base -n istio-system --wait
    helm install istiod istio/istiod -n cluster-ingress --set global.istioNamespace="cluster-ingress" --set meshConfig.accessLogFile="/dev/stdout"  --wait

Installation Instructions
+++++++++++++++++++++++++

Create a `cluster-ingress` namespace with image pull permissions from the Artifactory docker repository:

.. code-block:: bash

    kubectl create ns cluster-ingress

    kubectl create secret docker-registry docker-reg-cred \
        --docker-server=digitalasset-canton-network-docker.jfrog.io \
        --docker-username=${ARTIFACTORY_USER} \
        --docker-password=${ARTIFACTORY_PASSWORD} \
        -n cluster-ingress

    kubectl patch serviceaccount default -n cluster-ingress \
        -p '{"imagePullSecrets": [{"name": "docker-reg-cred"}]}'


Ensure that there is a cert-manager certificate available in a secret
named ``cn-net-tls``.  An example of a suitable certificate
definition:

.. code-block:: yaml

    apiVersion: cert-manager.io/v1
    kind: Certificate
    metadata:
       name: cn-certificate
       namespace: cluster-ingress
    spec:
        dnsNames:
        - '*.sv.svc.YOUR_HOSTNAME'
        issuerRef:
            name: letsencrypt-production
        secretName: cn-net-tls


Create a file named ``istio-gateway-values.yaml`` with the following content
(Tip: on GCP you can get the cluster IP from ``gcloud compute addresses list``):

.. code-block:: yaml

    service:
        loadBalancerIP: "YOUR_CLUSTER_IP"
        loadBalancerSourceRanges:
            - "35.194.81.56/32"
            - "35.198.147.95/32"
            - "35.189.40.124/32"
            - "34.132.91.75/32"



And install it to your cluster:

.. code-block:: bash

    helm install istio-ingress istio/gateway -n cluster-ingress -f istio-gateway-values.yaml


A reference Helm chart installing a gateway that uses this service is also provided.
To install it, run the following (assuming the environment variable `YOUR_HOSTNAME` is set to your hostname):

.. code-block:: bash

    helm install cluster-gateway canton-network-helm/cn-istio-gateway -n cluster-ingress --version ${CHART_VERSION} --set cluster.hostname=${YOUR_HOSTNAME}

This gateway terminates tls using the secret that you configured above, and exposes raw http traffic in its outbound port 443.
Istio VirtualServices can now be created to route traffic from there to the required pods within the cluster.
Another reference Helm chart is provided for that, which can be installed using:


.. code-block:: bash

    helm install cluster-ingress-sv canton-network-helm/cn-cluster-ingress-runbook -n sv --version ${CHART_VERSION} -f cn-node-0.1.0-SNAPSHOT/examples/sv-helm/sv-cluster-ingress-values.yaml



Configuring the Cluster Egress
-------------------------------

Here is a list of destinations of all outbound traffic from the Super Validator node.
This list is useful for an SV that wishes to limit egress to only allow the minimum necessary outbound traffic.

====================== ================================================================================================ ========= ==============
Destination            Url                                                                                              Protocol  Source pod
---------------------- ------------------------------------------------------------------------------------------------ --------- --------------
Sponsor SV             sv.sv-1.svc.<TARGET_CLUSTER>.network.canton.global:443                                           HTTPS     sv-app
Sponsor SV Sequencer   sequencer.sv-1.svc.<TARGET_CLUSTER>.network.canton.global:443                                    HTTPS     participant
Sponsor SV Scan        scan.sv-1.svc.<TARGET_CLUSTER>.network.canton.global:443                                         HTTPS     validator-app
CometBft P2P           CometBft p2p IPs and ports 26016, 26026, 26036, 26046, 26096                                     TCP       cometbft
CometBft JSON RPC      sv.sv-1.svc.<TARGET_CLUSTER>.network.canton.global:443/api/sv/v0/admin/domain/cometbft/json-rpc  HTTPS     cometbft
====================== ================================================================================================ ========= ==============

At present, we designate the founding SV as the sponsor SV. However, in the long term, any onboarded SV can function as a sponsor SV.

.. _helm-sv-wallet-ui:

Logging into the wallet UI
--------------------------

After you deploy your ingress, open your browser at
https://wallet.sv.svc.YOUR_HOSTNAME and login using the
credentials for the user that you configured as
``validatorWalletUser`` earlier. You will be able to see your balance
increase as mining rounds advance every 2.5 minutes and you will see
``sv_reward_collected`` entries in your transaction history.
Once logged in one should see the transactions page.

.. image:: images/wallet_home.png
  :width: 600
  :alt: After logged in into the wallet UI

.. _helm-cns-web-ui:

Logging into the CNS UI
-----------------------------

You can open your browser at
https://cns.sv.svc.YOUR_HOSTNAME and login using the
credentials for the user that you configured as
``validatorWalletUser`` earlier. You will be able to register a name on the
Canton Name Service.

.. image:: images/cns_home.png
  :width: 600
  :alt: After logged in into the CNS UI

.. _local-sv-web-ui:

Logging into the SV UI
----------------------

Open your browser at https://sv.sv.svc.YOUR_HOSTNAME to login to the SV Operations user interface.
You can use the credentials of the ``validatorWalletUser`` to login. These are the same credentials you used for the wallet login above. Note that only Super validators will be able to login.
Once logged in one should see a page with some SV collective information.

.. image:: images/sv_home.png
  :width: 600
  :alt: After logged in into the sv UI

The SV UI presents also some useful debug information for the CometBFT node. To see it, click on the "CometBFT Debug Info" tab.
If your CometBFT is configured correctly, and it has connectivity to all other nodes, you should see ``n_peers`` that is equal to the size of the SVC, excluding your own node,
and you should see all peer SV members listed as peers (their human-friendly names will be listed in the ``moniker`` fields).

.. _sv-ui-global-domain:

The SV UI also presents the status of your global domain node. To see it, click on the "Domain Node Status" tab.

.. _helm-scan-web-ui:

Observing the Canton Coin Scan UI
---------------------------------

The Canton Coin Scan app is a public-facing application that provides summary information regarding Canton Coin activity on the network.
A copy of it is hosted by each Super Validator. Open your browser at https://scan.sv.svc.YOUR_HOSTNAME to see your instance of it.

Note that after spinning up the application, it may take several minutes before data is available (as it waits to see a mining round opening and closing).
In the top-right corner of the screen, see the message starting with "The content on this page is computed as of round:".
If you see a round number, then the data in your scan app is up-to-date.
If, instead, you see "??", that means that the backend is working, but does not yet expose any useful information. It should turn into a round number within a few minutes.
A "--" as a round number indicates a problem. It may be very temporary while the data is being fetched from the backend, but if it persists for more than that,
please inspect the browser logs and reach out to Digital Asset support as needed.

Note that as of now, each instance of the Scan app backend aggregates only Canton Coin activity occuring while the app is up and ingesting ledger updates.
This will be changed in future updates, where the Scan app will guarantee correctness against all data since network start.
At that point, data in different instances of the Scan app (hosted by different Super Validators) will always be consistent.
This allows the public to inspect multiple Scan UIs and compare their data, so that they do not need to trust a single Super Validator.

.. _participant-identities-backup:

Transitioning Across Network Resets
-----------------------------------

Please consult the :ref:`relevant section of the validator runbook <old-validator-continuity>` on how to backup the identity of your participant,
to ensure that coin balances associated with your SV's validator node (which likely includes rewards earned by your SV nodes) are preserved across a `TestNet` reset.
Please consult the :ref:`relevant section above <sv-participant-identities-restore>` on how to restore from an existing participant identities backup.

Note that coin balances are currently not persisted across `DevNet` resets.
Participant identities can still be restored across `DevNet` resets, using the steps referenced above,
but doing so will yield no benefit in terms of recovered coin balances.
It can still be useful for testing, of course.
One way to verify that your participant identities were restored correctly is to observe that the ``svPartyId`` shown in your SV UI is identical to your ``svPartyId`` at the time of your identities backup.
