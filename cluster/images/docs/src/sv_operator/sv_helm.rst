.. _sv-helm:

Kubernetes-Based Deployment of an SV node
=========================================

This section describes deploying an SV node in kubernetes using Helm
charts.  The Helm charts deploy a complete node and connect it to a
target cluster, either `DevNet` or `TestNet`.

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

5) You should have completed the self hosted validator setup,
   including Auth 0 setup.


Preparing a Cluster for Installation
------------------------------------

Ensure that your local helm installation has access to the Digital Asset Helm chart repository:

.. code-block:: bash

    helm repo add canton-network-helm \
        https://digitalasset.jfrog.io/artifactory/api/helm/canton-network-helm \
        --username ${ARTIFACTORY_USER} \
        --password ${ARTIFACTORY_PASSWORD}

Create the three application namespaces within Kubernetes and ensure they have image pull credentials for fetching images from the Digital Asset Docker image repository:

.. code-block:: bash

    for ns_name in docs svc sv-1; do
        kubectl create ns ${ns_name}

        kubectl create secret docker-registry docker-reg-cred \
            --docker-server=digitalasset-canton-network-docker.jfrog.io \
            --docker-username=${ARTIFACTORY_USER} \
            --docker-password=${ARTIFACTORY_PASSWORD} \
            --docker-email=${ARTIFACTORY_USER_EMAIL} \
            -n ${ns_name}

        kubectl patch serviceaccount default -n ${ns_name} \
            -p '{"imagePullSecrets": [{"name": "docker-reg-cred"}]}'
    done


Installing the Software
-------------------------

Install the Helm charts needed to start an SV node connected to the cluster.
To make these commands work, you will need to meet a few
preconditions. The first is that there needs to be an environment
variable defined to refer to the version of the Helm charts necessary
to connect to this environment:

|chart_version_set|

There should also be a file, ``participant-values.yaml``, that refers to
the domain in the cluster to which you are connecting. As in other
sections of this runbook, please replace ``TARGET_CLUSTER`` with
``dev`` or ``test`` per the cluster to which you are connecting. The
``participant-values.yaml`` file also contains an additional configuration
block for specifying the Auth0 instance. This will need to be updated
to match your configuration.

.. code-block:: yaml

    globalDomain:
      alias: global
      url: http://TARGET_CLUSTER.network.canton.global:5008
    auth:
      jwksEndpoint: "https://YOUR_INSTANCE_NAME.us.auth0.com/.well-known/jwks.json"
      targetAudience: "https://canton.network.global"

An SV node includes a validator app so you also need to configure
that. Create a file called ``validator-values.yaml`` with the
following content.

.. code-block:: yaml

    participant_address: "participant.svc"
    svSponsorPort: "5014"
    svSponsorAddress: "https://TARGET_CLUSTER.network.canton.global"
    scanPort: "5012"
    scanAddress: "https://TARGET_CLUSTER.network.canton.global"
    # Replace with the user id in your IAM that you want to use to log into
    # the wallet as the SV party.
    validatorWalletUser: "SV_WALLET_USER_ID"
    clusterUrl: "TARGET_CLUSTER.network.canton.global"
    auth:
      audience: https://canton.network.global
      jwksUrl: https://YOUR_INSTANCE_NAME.us.auth0.com/.well-known/jwks.json

The authentication credentials should be defined in a file named
``sv-values.yaml``. If you haven't done so yet, please first follow the instructions in
the :ref:`Generating an SV Identity<sv-identity>` section to obtain and register a name and keypair for your SV.


.. code-block:: yaml

    participant_address: "participant.svc"
    joinWithKeyOnboarding:
      sponsorApiPort: 5014
      sponsorApiUrl: "https://TARGET_CLUSTER.network.canton.global"
      svcApiAddress: "TARGET_CLUSTER.network.canton.global"
      keyName: ... key name goes here ...
      publicKey: ... public key goes here ...
      privateKey: ... private key goes here ...


With this file in place, you can execute the following helm commands
in sequence. It's generally a good idea to wait until each deployment
reaches a stable state prior to moving on to the next step.

.. code-block:: bash

    helm repo update
    helm install docs canton-network-helm/cn-docs -n docs --version ${CHART_VERSION}
    helm install postgres canton-network-helm/cn-postgres -n svc --version ${CHART_VERSION}
    helm install participant canton-network-helm/cn-participant -n svc --version ${CHART_VERSION} -f participant-values.yaml
    helm install validator canton-network-helm/cn-validator-node -n sv-1 --version ${CHART_VERSION} -f validator-values.yaml
    helm install sv-1 canton-network-helm/cn-sv-node -n sv-1 --version ${CHART_VERSION} -f sv-values.yaml

Once this is running, you should be able to inspect the state of the
cluster and observe pods running in each of the three new
namespaces. A typical query might look as follows:

.. code-block:: bash

    $ kubectl get pods --all-namespaces |grep -v kube-system
    NAMESPACE      NAME                                                       READY   STATUS    RESTARTS   AGE
    docs           docs-86647d56dd-97d64                                      1/1     Running   0          34m
    docs           gcs-proxy-86bf867fdc-c2tcm                                 1/1     Running   0          34m
    sv-1           sv-app-59d4d499dd-nf4mj                                    1/1     Running   0          57m
    sv-1           validator-app-68fc94d87f-fjz4z                             1/1     Running   0          17m
    sv-1           wallet-web-ui-7c94df497f-c2pb4                             1/1     Running   0          17m
    svc            participant-576cc9bc74-22wzx                               3/3     Running   0          151m
    svc            postgres-0                                                 1/1     Running   0          151m

Note also that ``Pod`` restarts may happen during bringup,
particualrly if all helm charts are deployed at the same time. The
``cn-sv-node`` cannot start until ``participant`` is running and
``participant`` cannot start until ``postgres`` is running.


Configuring the Cluster Ingress
===============================

Internet ingress configuration is often specific to the network configuration and scenario of the cluster being configured. To illustrate the basic requirements of a Canton Network ingress, we have provided a Helm chart that configures the above cluster for external network access.

Requirements:
-------------

cert-manager must be available in the cluster (See `cert-manager documentation <https://cert-manager.io/docs/installation/helm/>`_)

Installation Instructions:
--------------------------

Create a cluster-ingress namespace with image pull permissions from the Artifactory docker repository:

.. code-block:: bash

    kubectl create ns cluster-ingress

    kubectl create secret docker-registry docker-reg-cred \
        --docker-server=digitalasset-canton-network-docker.jfrog.io \
        --docker-username=${ARTIFACTORY_USER} \
        --docker-password=${ARTIFACTORY_PASSWORD} \
        --docker-email=${ARTIFACTORY_USER_EMAIL} \
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
        - cn-cluster.YOUR_DOMAIN.com
        - '*.cn-cluster.YOUR_DOMAIN.com'
        - '*.validator1.cn-cluster.YOUR_DOMAIN.com'
        - '*.splitwell.cn-cluster.YOUR_DOMAIN.com'
        - '*.svc.cn-cluster.YOUR_DOMAIN.com'
        - '*.sv-1.svc.cn-cluster.YOUR_DOMAIN.com'
        issuerRef:
            name: letsencrypt-production
        secretName: cn-net-tls


The ingress configuration is specified in a YAML file with the
following contents. (externalIPRanges can be extended with additional
IP addresses you would like to allow to connect to your cluster). The
instructions below expect this file to be named ``ingress-values.yaml``

.. code-block:: yaml

    enableIngressModes: sv sv-external
    cluster:
        networkSettings:
            externalIPRanges:
                - "35.194.81.56/32"
                - "35.198.147.95/32"
                - "35.189.40.124/32"
                - "34.132.91.75/32"
        ipAddress: "YOUR_CLUSTER_IP"

On GCP you can get the cluster IP from ``gcloud compute addresses list``.

Install the Ingress Helm Chart:

.. code-block:: bash

    helm install cluster-ingress canton-network-helm/cn-cluster-ingress \
        -n cluster-ingress \
        -f ingress-values.yaml

.. _helm-sv-wallet-ui:

Logging into the wallet UI
--------------------------

After you deploy your ingress, open your browser at
https://wallet.sv-1.svc.cn-cluster.YOUR_DOMAIN.com and login using the
credentials for the user that you configured as
``validatorWalletUser`` earlier. You will be able to see your balance
increase as mining rounds advance every 2.5 minutes and you will see
``sv_reward_collected`` entries in your transaction history.
