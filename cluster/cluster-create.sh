#!/bin/sh
set -euo pipefail

source config

source lib/gcloud-setup

#### Check for existing cluster

N_CLUSTERS=$(gcloud container clusters list --filter "name:${GCP_CLUSTER_NAME}"  --format 'value(name)' | wc -l)

if [ "${N_CLUSTERS}" -gt "0" ]; then
    echo "Cluster already exists, ending."
    exit 0
fi

#### Cluster does not already exist, create it and apply our configuration.

echo "Creating cluster: ${GCP_CLUSTER_NAME}"

gcloud container clusters create ${GCP_CLUSTER_NAME}

echo "Cluster created, connecting and applying configuration."

source lib/gcloud-connect
source lib/gcloud-apply


