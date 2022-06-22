#!/bin/sh
set -euo pipefail

source config

source lib/gcloud-setup

#### Check for existing cluster

CLUSTER=$(gcloud container clusters list --filter "name:${GCP_CLUSTER_NAME}"  --format 'value(name)')

if [ -n "${CLUSTER}" ]; then
    echo "Cluster already exists, ending."
    exit 0
fi

#### Cluster does not already exist, create it and apply our configuration.

echo "Creating cluster: ${GCP_CLUSTER_NAME}"

gcloud container clusters create ${GCP_CLUSTER_NAME}

#### Check for IP

CLUSTER_IP=$(gcloud compute addresses list --global --filter "name:${GCP_IP_NAME}" --format 'value(address)')

if [ -z "${CLUSTER_IP}" ]; then
    echo "Allocating IP address."
    gcloud compute addresses create ${GCP_IP_NAME} --global
fi

CLUSTER_IP=$(gcloud compute addresses list --global --filter "name:${GCP_IP_NAME}" --format 'value(address)')

echo "Cluster created (IP: ${CLUSTER_IP}), applying configuration."

source lib/gcloud-connect
source lib/gcloud-apply


