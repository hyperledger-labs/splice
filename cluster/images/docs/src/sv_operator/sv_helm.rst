.. _sv-helm:

Kubernetes-Based Deployment of an SV node
=========================================

This section describes deploying an SV node in kubernetes using Helm charts.
At the moment, the Helm charts deploy a standalone network with a single SV node and a domain, disconnected from the |cn_cluster| network.
This page will be updated soon with instructions for Helm charts that spin up a node that is connected to the actual |cn_cluster| as
one super-validator on that node.

Requirements
------------

1) Access to the following two Artifactory repositories:

    a. `Canton Network Docker repository <https://digitalasset.jfrog.io/ui/native/canton-network-docker>`_
    b. `Canton Network Helm repository <https://digitalasset.jfrog.io/ui/native/canton-network-helm/>`_

2) A running Kubernetes cluster in which you have administrator access to create and manage namespaces.
3) A development workstation with the following:

    a. kubectl - At least v1.26.1
    b. helm - At least v3.11.1

Installation instructions
-------------------------

Ensure that your local helm installation has access to the Digital Asset Helm chart repository: ::

    helm repo add canton-network-helm \
        https://digitalasset.jfrog.io/artifactory/api/helm/canton-network-helm \
        --username ${ARTIFACTORY_USER} \
        --password ${ARTIFACTORY_PASSWORD}

Create the three application namespaces within Kubernetes and ensure they have image pull credentials for fetching images from the Digital Asset Docker image repository: ::

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

Install the Helm charts needed to start a standalone Canton Network cluster: ::

    helm install docs canton-network-helm/cn-docs -n docs
    helm install postgres canton-network-helm/cn-postgres -n svc
    helm install global-domain canton-network-helm/cn-domain -n svc
    helm install participant canton-network-helm/cn-participant -n svc
    helm install svc canton-network-helm/cn-svc -n svc
    helm install sv-1-sv-app canton-network-helm/cn-sv-node -n sv-1 --set bootstrapType=found-collective

Once this is running, you should be able to inspect the state of the cluster and observe pods running in each of the three new namespaces.

Configuring the Cluster Ingress
===============================

Internet ingress configuration is often specific to the network configuration and scenario of the cluster being configured. To illustrate the basic requirements of a Canton Network ingress, we have provided a Helm chart that configures the above cluster for external network access.

Requirements:
-------------

cert-manager must be available in the cluster (See `cert-manager documentation <https://cert-manager.io/docs/installation/helm/>`_)

Installation Instructions:
--------------------------

Create a cluster-ingress namespace with image pull permissions from the Artifactory docker repository: ::

    kubectl create ns cluster-ingress

    kubectl create secret docker-registry docker-reg-cred \
        --docker-server=digitalasset-canton-network-docker.jfrog.io \
        --docker-username=${ARTIFACTORY_USER} \
        --docker-password=${ARTIFACTORY_PASSWORD} \
        --docker-email=${ARTIFACTORY_USER_EMAIL} \
        -n cluster-ingress

    kubectl patch serviceaccount default -n cluster-ingress \
        -p '{"imagePullSecrets": [{"name": "docker-reg-cred"}]}'


Ensure that there is a cert-manager certificate available in a secret named cn-net-tls.
An example of a suitable certificate definition: ::

    apiVersion: cert-manager.io/v1
    kind: Certificate
    metadata:
    name: cn-sqlscratch-certificate
    namespace: cluster-ingress
    spec:
        dnsNames:
        - cn-cluster.yourco.com
        - '*.cn-cluster.yourco.com'
        - '*.validator1.cn-cluster.yourco.com'
        - '*.splitwell.cn-cluster.yourco.com'
        - '*.svc.cn-cluster.yourco.com'
        - '*.sv-1.svc.cn-cluster.yourco.com'
        issuerRef:
            name: letsencrypt-production
        secretName: cn-net-tls

Install the Ingress Helm Chart: ::

    helm install cluster-ingress canton-network-helm/cn-cluster-ingress \
        -n cluster-ingress \
        -f ingress-values.yaml

Where ingress-values.yaml has the following contents. (externalIPRanges can be extended with additional IP addresses you would like to allow to connect to your cluster): ::

    enableIngressModes: sv
    cluster:
        networkSettings:
            externalIPRanges:
                - "35.194.81.56/32"
                - "35.198.147.95/32"
                - "35.189.40.124/32"
                - "34.132.91.75/32"


