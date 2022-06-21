#!/bin/sh

GCP_PROJECT_NAME=cn-devnet
GCP_CLUSTER_NAME=cn-devnet

#### Identify the ID of the project to be used

GCP_PROJECT_ID=$(gcloud projects list --filter "name:${GCP_PROJECT_NAME}" --format 'value(project_id)')

if [ -z "${GCP_PROJECT_ID}" ]; then
    echo "Cannot find GCP Project with expected name: ${GCP_PROJECT_NAME}"
    exit 1
fi

echo "GCP Project ID: ${GCP_PROJECT_ID}"

gcloud container clusters get-credentials \
       --region us-central1 \
       --project ${GCP_PROJECT_ID} \
       ${GCP_CLUSTER_NAME}
