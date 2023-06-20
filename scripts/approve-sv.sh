#!/usr/bin/env bash

set -eou pipefail

if [ "$#" -ne 2 ]
then
   >&2 echo "Expected 2 arguments but got $#"
   >&2 echo "Usage: \$REPO_ROOT/scripts/approve-sv.sh sv_name sv_public_key"
   exit 1
fi

candidateName=$1
candidateKey=$2

if [ -z "${GCP_CLUSTER_BASENAME:-}" ]
then
    >&2 echo "GCP_CLUSTER_BASENAME was not set, make sure you run this from within cluster/deployment/TARGET_CLUSTER"
    exit 1
fi

AUTH0_MANAGEMENT_API_URL=https://canton-network-dev.us.auth0.com/api/v2

MANAGEMENT_TOKEN=$(curl -sSL --fail-with-body \
    --url https://canton-network-dev.us.auth0.com/oauth/token \
    -H 'Content-Type: application/json' \
    -d "{\"client_id\": \"$AUTH0_CN_MANAGEMENT_API_CLIENT_ID\",\"client_secret\": \"$AUTH0_CN_MANAGEMENT_API_CLIENT_SECRET\",\"audience\": \"$AUTH0_MANAGEMENT_API_URL/\",\"grant_type\":\"client_credentials\"}" | jq -r '.access_token')

ALL_APPS="$(curl -sSL --fail-with-body -H "Authorization: Bearer $MANAGEMENT_TOKEN" ${AUTH0_MANAGEMENT_API_URL}/clients)"

client_ids=(
    [1]='OBpJ9oTyOLuAKF0H2hhzdSFUICt0diIn'
    [2]='rv4bllgKWAiW9tBtdvURMdHW42MAXghz'
    [3]='SeG68w0ubtLQ1dEMDOs4YKPRTyMMdDLk'
    [4]='CqKgSbH54dqBT7V1JbnCxb6TfMN8I1cN'
)

for i in 1 2 3 4
do
    client_id="${client_ids[$i]}"
    client_secret=$(jq -r ".[] | select(.client_id==\"$client_id\") | .client_secret" <<< "$ALL_APPS")

    echo "Querying token to access SV $i API"


    sv_token=$(curl -sSL --fail-with-body https://canton-network-dev.us.auth0.com/oauth/token \
      -H 'content-type: application/json' \
      -d "{\"client_id\":\"$client_id\",\"client_secret\":\"$client_secret\",\"audience\":\"https://canton.network.global\",\"grant_type\":\"client_credentials\"}" | jq '.access_token' -r)

    echo "Sending new key to SV $i API"

    curl -sSL --fail-with-body "https://sv.sv-$i.svc.$GCP_CLUSTER_BASENAME.network.canton.global/api/v0/sv/admin/sv/identity/approve" -d "{\"candidateName\": \"$candidateName\", \"candidateKey\": \"$candidateKey\"}" -H "Authorization: Bearer $sv_token" -H "Content-Type: application/json"
done
