.. _sv-helm:

Kubernetes-Based Deployment of a Super Validator node
=====================================================

This section describes deploying a Super Validator (SV) node in kubernetes using Helm
charts.  The Helm charts deploy a complete node and connect it to a
target cluster. We currently operate two clusters: `TestNet` which is upgraded weekly with a stable release,
and `DevNet` which is upgraded nightly with a nightly dev release. Please use `TestNet` unless you
have a specific reason not to, as `DevNet` may be unstable, and will also introduce breaking changes on
a daily basis.

Note that there is not yet data retention from release to release in
either `DevNet` or `TestNet`. This will change as the models and API's
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

4) You should have an SV key pair generated and approved by Digital Asset.
See instructions in the :ref:`Generating an SV identity section <sv-identity>`.

5) You should have completed the self hosted validator setup,
   including Auth0 setup. Dedicated instructions can be found in the :ref:`Self-Hosted Validator section <self_hosted_validator>`

6) Your cluster either needs to be connected to the GCP DA Canton
   DevNet VPN or you need a static egress IP. In the latter case,
   please provide that IP address to your contact at Digital Asset to
   add it to the firewall rules.

7) Please download the release artifacts containing the sample Helm value files, from here: |bundle_download_link|, and extract the bundle:

.. parsed-literal::

  tar xzvf |version|\_cn-node-0.1.0-SNAPSHOT.tar.gz



Preparing a Cluster for Installation
------------------------------------

In the following, you will need your Artifactory credentials from
https://digitalasset.jfrog.io/ui/user_profile. Based on that, set the following environment variables.

====================== ==========================================================================================
Name                   Value
---------------------- ------------------------------------------------------------------------------------------
ARTIFACTORY_USER       Your Artifactory user name shown at the top right.
ARTIFACTORY_PASSWORD   Your Artifactory API key. If you don't have one you can generate one on your profile page.
====================== ==========================================================================================

Ensure that your local helm installation has access to the Digital Asset Helm chart repository:

.. code-block:: bash

    helm repo add canton-network-helm \
        https://digitalasset.jfrog.io/artifactory/api/helm/canton-network-helm \
        --username ${ARTIFACTORY_USER} \
        --password ${ARTIFACTORY_PASSWORD}

Create the application namespace within Kubernetes and ensure is has image pull credentials for fetching images from the Digital Asset Artifactory repository used for Docker images.

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
Currently, these are the SV web UI and the Wallet web UI.
You might be required to whitelist a range of URLs on your OIDC provider, such as "Allowed Callback URLs", "Allowed Logout URLs", "Allowed Web Origins", and "Allowed Origins (CORS)".
If you are using the ingress configuration of this runbook, the correct URLs to configure here are
``https://sv.sv.svc.YOUR_CLUSTER_URL`` (for the SV web UI) and
``https://wallet.sv.svc.YOUR_CLUSTER_URL`` (for the Wallet web UI).
An identifier that is unique to the user must be set via the `sub` field of the issued JWT.
On some occasions, this identifier will be used as a user name for that user on your SV node's Canton participant.
In :ref:`helm-sv-install`, you will be required to configure a user identifier as the ``validatorWalletUser`` -
make sure that whatever you configure there matches the contents of the `sub` field of JWTs issued for that user.

*All* JWTs issued for use with your SV node:

- must be signed using the RS256 signing algorithm
- must set the audience (`aud`) field to exactly ``https://canton.network.global`` [#aud]_

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
SV_CLIENT_SECRET        The client id of the Auth0 app for the SV app backend
WALLET_UI_CLIENT_ID     The client id of the Auth0 app for the wallet UI.
SV_UI_CLIENT_ID         The client id of the Auth0 app for the SV UI.
======================= ===========================================================================

We are going to use these values, exported to environment variables named as per the `Name` column, in :ref:`helm-sv-auth-secrets-config` and :ref:`helm-sv-install`.

In case you are facing trouble with setting up your (non-Auth0) OIDC provider,
it can be beneficial to skim the instructions in :ref:`helm-sv-auth0` as well, to check for functionality or configuration details that your OIDC provider setup might be missing.

.. [#reach] The URL must be reachable from the Canton participant, validator app and SV app running in your cluster, as well as from all web browsers that should be able to interact with the SV and wallet UIs.

.. [#aud] This is currently the only audience supported. In the future, the audience will be made customizable.

    .. TODO(#2052) use a unique audience for each app

.. _helm-sv-auth0:

Configuring an Auth0 Tenant
+++++++++++++++++++++++++++

To configure `Auth0 <https://auth0.com>`_ as your SV's OIDC provider, perform the following:

1. Create an Auth0 tenant for your SV
2. Create an Auth0 API that controls access to the ledger API:

    a. Navigate to Applications > APIs and click "Create API". Set name to ``Daml Ledger API``, set identifier to ``https://canton.network.global`` [#aud]_.
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
       If you're using the ingress configuration of this runbook, that would be ``https://sv.sv.svc.YOUR_CLUSTER_URL``.
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
     If you're using the ingress configuration of this runbook, that would be ``https://wallet.sv.svc.YOUR_CLUSTER_URL``.

Please refer to Auth0's `own documentation on user management <https://auth0.com/docs/manage-users>`_ for pointers on how to set up end-user accounts for the two web UI applications you created.
Note that you will need to create at least one such user account for completing the steps in :ref:`helm-sv-install` - for being able to log in as your SV node's administrator.
You will be asked to obtain the user identifier for this user account.
It can be found in the Auth0 interface under User Management -> Users -> your user's name -> user_id (a field right under the user's name at the top).

We will use the environment variables listed in the table below to refer to aspects of your Auth0 configuration:

======================= ===========================================================================
Name                    Value
----------------------- ---------------------------------------------------------------------------
OIDC_AUTHORITY_URL      ``https://AUTH0_TENANT_NAME.us.auth0.com``
VALIDATOR_CLIENT_ID     The client id of the Auth0 app for the validator app backend
VALIDATOR_CLIENT_SECRET The client secret of the Auth0 app for the validator app backend
SV_CLIENT_ID            The client id of the Auth0 app for the SV app backend
SV_CLIENT_SECRET        The client id of the Auth0 app for the SV app backend
WALLET_UI_CLIENT_ID     The client id of the Auth0 app for the wallet UI.
SV_UI_CLIENT_ID         The client id of the Auth0 app for the SV UI.
======================= ===========================================================================

The ``AUTH0_TENANT_NAME`` is the name of your Auth0 tenant as shown at the top left of your Auth0 project.
You can obtain the client ID and secret of each Auth0 app from the settings pages of that app.

.. _helm-sv-auth-secrets-config:

Configuring Authentication on your SV Node
++++++++++++++++++++++++++++++++++++++++++

We are now going to configure your SV node software based on the OIDC provider configuration values your exported to environment variables at the end of either :ref:`helm-sv-auth-requirements` or :ref:`helm-sv-auth0`.
(Note that some authentication-related configuration steps are also included in :ref:`helm-sv-install`.)

The following two kubernetes secrets will instruct the participant to create service users for your validator and SV apps:

.. code-block:: bash

    kubectl create --namespace sv secret generic cn-app-sv1-validator-ledger-api-auth \
        "--from-literal=ledger-api-user=${VALIDATOR_CLIENT_ID}@clients"

    kubectl create --namespace sv secret generic cn-app-sv1-ledger-api-auth \
        "--from-literal=ledger-api-user=${SV_CLIENT_ID}@clients"

For technical reasons, please also create the following dummy secrets (a requirement that will be removed in the near future):

.. code-block:: bash

    kubectl create --namespace sv secret generic cn-app-scan-ledger-api-auth "--from-literal=ledger-api-user=dummy"
    kubectl create --namespace sv secret generic cn-app-directory-ledger-api-auth "--from-literal=ledger-api-user=dummy"
    kubectl create --namespace sv secret generic cn-app-svc-ledger-api-auth "--from-literal=ledger-api-user=dummy"


The SV app is configured with a secret as follows:

.. code-block:: bash

    kubectl create --namespace sv secret generic cn-app-sv-ledger-api-auth \
        "--from-literal=ledger-api-user=${SV_CLIENT_ID}@clients" \
        "--from-literal=url=${OIDC_AUTHORITY_URL}/.well-known/openid-configuration" \
        "--from-literal=client-id=${SV_CLIENT_ID}" \
        "--from-literal=client-secret=${SV_CLIENT_SECRET}"


The validator app backend requires the following secret.

.. code-block:: bash

    kubectl create --namespace sv secret generic cn-app-validator-ledger-api-auth \
        "--from-literal=ledger-api-user=${VALIDATOR_CLIENT_ID}@clients" \
        "--from-literal=url=${OIDC_AUTHORITY_URL}/.well-known/openid-configuration" \
        "--from-literal=client-id=${VALIDATOR_CLIENT_ID}" \
        "--from-literal=client-secret=${VALIDATOR_CLIENT_SECRET}"

To setup the wallet and SV UI, create the following two secrets.

.. code-block:: bash

    kubectl create --namespace sv secret generic cn-app-wallet-ui-auth \
        "--from-literal=url=${OIDC_AUTHORITY_URL}" \
        "--from-literal=client-id=${WALLET_UI_CLIENT_ID}"

    kubectl create --namespace sv secret generic cn-app-sv-ui-auth \
        "--from-literal=url=${OIDC_AUTHORITY_URL}" \
        "--from-literal=client-id=${SV_UI_CLIENT_ID}"

.. _helm-sv-install:

Installing the Software
-----------------------

Install the Helm charts needed to start an SV node connected to the cluster.
To make these commands work, you will need to meet a few
preconditions. The first is that there needs to be an environment
variable defined to refer to the version of the Helm charts necessary
to connect to this environment:

Note that because of the lack data retention from release to release
in either `DevNet` or `TestNet`, every release should be installed
from scratch into a completely clean environment. Data should not be
retained, and there is no current support for helm chart upgrades or
similar.

|chart_version_set|

Please modify the file ``cn-node-0.1.0-SNAPSHOT/examples/sv-helm/participant-values.yaml`` as follows:

- Replace ``TARGET_CLUSTER`` in the `globalDomain.url` entry with |cn_cluster|, per the cluster to which you are connecting.
- Update the `auth.jwksUrl` entry to point to your auth provider's JWK set document by replacing ``OIDC_AUTHORITY_URL`` with your auth provider's OIDC URL, as explained above.

An SV node includes a validator app so you also need to configure
that. Please modify the file ``cn-node-0.1.0-SNAPSHOT/examples/sv-helm/validator-values.yaml`` as follows:

- Replace all instances of ``TARGET_CLUSTER`` with |cn_cluster|, per the cluster to which you are connecting.
- Replace ``SV_WALLET_USER_ID`` with the user ID in your IAM that you want to use to log into the wallet as the SV party. Note that this should be the full user id, e.g., ``auth0|43b68e1e4978b000cefba352``, *not* only the suffix ``43b68e1e4978b000cefba352``
- Update the `auth.jwksUrl` entry to point to your auth provider's JWK set document by replacing ``OIDC_AUTHORITY_URL`` with your auth provider's OIDC URL, as explained above.

The private and public key for your SV are defined in a K8s secret.
If you haven't done so yet, please first follow the instructions in
the :ref:`Generating an SV Identity<sv-identity>` section to obtain
and register a name and keypair for your SV. Replace
``YOUR_PUBLIC_KEY`` and ``YOUR_PRIVATE_KEY`` with the ``public-key``
and ``private-key`` values obtained as part of generating your SV
idenitty.

.. code-block:: bash

    kubectl create secret --namespace sv generic cn-app-sv-key \
        --from-literal=public=YOUR_PUBLIC_KEY \
        --from-literal=private=YOUR_PRIVATE_KEY

For configuring your sv app, please modify the file ``cn-node-0.1.0-SNAPSHOT/examples/sv-helm/sv-values.yaml`` as follows:

- Replace all instances of ``TARGET_CLUSTER`` with |cn_cluster|, per the cluster to which you are connecting.
- Replace ``YOUR_SV_NAME`` with the name you chose when creating the SV identity (this must be an exact match of the string for your SV to be approved to onboard)
- Update the `auth.jwksUrl` entry to point to your auth provider's JWK set document by replacing ``OIDC_AUTHORITY_URL`` with your auth provider's OIDC URL, as explained above.

With these files in place, you can execute the following helm commands
in sequence. It's generally a good idea to wait until each deployment
reaches a stable state prior to moving on to the next step.

.. code-block:: bash

    helm repo update
    helm install postgres canton-network-helm/cn-postgres -n sv --version ${CHART_VERSION} --wait
    helm install participant canton-network-helm/cn-participant -n sv --version ${CHART_VERSION} -f cn-node-0.1.0-SNAPSHOT/examples/sv-helm/participant-values.yaml --wait
    helm install validator canton-network-helm/cn-validator -n sv --version ${CHART_VERSION} -f cn-node-0.1.0-SNAPSHOT/examples/sv-helm/validator-values.yaml --wait
    helm install sv canton-network-helm/cn-sv-node -n sv --version ${CHART_VERSION} -f cn-node-0.1.0-SNAPSHOT/examples/sv-helm/sv-values.yaml --wait

Once this is running, you should be able to inspect the state of the
cluster and observe pods running in each of the three new
namespaces. A typical query might look as follows:

.. code-block:: bash

    $ kubectl get pods --all-namespaces |grep -v kube-system
    NAMESPACE         NAME                                                       READY   STATUS    RESTARTS      AGE
    cluster-ingress   external-proxy-998cb664c-k7dfb                             1/1     Running   0             37m
    sv                sv-app-7658c9fdd4-58xm6                                    1/1     Running   0             91m
    sv                sv-web-ui-84b6d7994c-w67rp                                 1/1     Running   0             91m
    sv                validator-app-b7fd68479-w4992                              1/1     Running   0             43m
    sv                wallet-web-ui-54c9ddbb8-nvkmp                              1/1     Running   0             43m
    sv                participant-6fdff7fc4-vzg8c                                3/3     Running   1 (72m ago)   72m
    sv                postgres-0                                                 1/1     Running   0             120m


Note also that ``Pod`` restarts may happen during bringup,
particularly if all helm charts are deployed at the same time. The
``cn-sv-node`` cannot start until ``participant`` is running and
``participant`` cannot start until ``postgres`` is running.

.. _helm-sv-ingress:


Configuring the Cluster Ingress
-------------------------------

The following routes should be configured in your cluster ingress controller:

* `https://wallet.sv.svc.<YOUR_HOSTNAME>` should be routed to pod `wallet-web-ui` in the `sv` namespace
* `https://wallet.sv.svc.<YOUR_HOSTNAME>/api/v0/validator/*` should be routed to port 5003 of pod `validator-app` in the `sv` namespace
* `https://sv.sv.svc.<YOUR_HOSTNAME>` should be routed to pod `sv-web-ui` in the `sv` namespace
* `https://sv.sv.svc.<YOUR_HOSTNAME>/api/v0/validator/*` should be routed to port 5014 of pod `sv-app` in the `sv` namespace

Internet ingress configuration is often specific to the network configuration and scenario of the
cluster being configured. To illustrate the basic requirements of an SV node ingress, we have
provided a Helm chart that configures the above cluster according to the routes above, as detailed in the sections below.


Requirements
++++++++++++

In order to install the reference charts, the following must be satisfied in your cluster:

* cert-manager must be available in the cluster (See `cert-manager documentation <https://cert-manager.io/docs/installation/helm/>`_)
* istio should be installed in the cluster (See `istio documentation <https://istio.io/latest/docs/setup/>`_)

Installation Instructions
+++++++++++++++++++++++++

Create a cluster-ingress namespace with image pull permissions from the Artifactory docker repository:

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

    loadBalancerIP: "YOUR_CLUSTER_IP"
    loadBalancerSourceRanges:
        - "35.194.81.56/32"
        - "35.198.147.95/32"
        - "35.189.40.124/32"
        - "34.132.91.75/32"



And install it to your cluster:

.. code-block:: bash

    helm repo add istio https://istio-release.storage.googleapis.com/charts
    helm repo update
    helm install istio-ingress istio/gateway -n cluster-ingress -f istio-gateway-values.yaml


A reference Helm chart that installs a gateway that uses this service is also provided.
To install it, run the following (assuming environment variable YOUR_HOSTNAME set to to your hostname):

.. code-block:: bash

    helm install cluster-gateway canton-network-helm/cn-istio-gateway -n cluster-ingress --set cluster.hostname=$YOUR_HOSTNAME


This gateway terminates tls using the secret that you configured above, and exposes raw http traffic in its outbound port 443.
Istio VirtualServices can now be created to route traffic from there to the required pods within the cluster.
Another reference Helm chart is provided for that, which can be installed using:


.. code-block:: bash

    helm install cluster-ingress-sv canton-network-helm/cn-cluster-ingress-sv -n cluster-ingress --set cluster.hostname=$YOUR_HOSTNAME --set cluster.svNamespace=sv


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

.. _local-sv-web-ui:

Logging into the SV UI
----------------------

Open your browser at https://sv.sv.svc.YOUR_HOSTNAME to login to the SV Operations user interface.
You can use the credentials of the ``validatorWalletUser`` to login. These are the same credentials you used for the wallet login above. Note that only Super validators will be able to login.
Once logged in one should see a page with some SV collective information.

.. image:: images/sv_home.png
  :width: 600
  :alt: After logged in into the sv UI
