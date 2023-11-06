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

AUTH0_CN_MANAGEMENT_API_URL=https://canton-network-dev.us.auth0.com
AUTH0_SV_MANAGEMENT_API_URL=https://canton-network-sv-test.us.auth0.com

AUTH0_CN_MANAGEMENT_TOKEN=$(curl -sSL --fail-with-body \
    --url "$AUTH0_CN_MANAGEMENT_API_URL/oauth/token" \
    -H 'Content-Type: application/json' \
    -d "{\"client_id\": \"$AUTH0_CN_MANAGEMENT_API_CLIENT_ID\",\"client_secret\": \"$AUTH0_CN_MANAGEMENT_API_CLIENT_SECRET\",\"audience\": \"$AUTH0_CN_MANAGEMENT_API_URL/api/v2/\",\"grant_type\":\"client_credentials\"}" | jq -r '.access_token')

AUTH0_SV_MANAGEMENT_TOKEN=$(curl -sSL --fail-with-body \
    --url "$AUTH0_SV_MANAGEMENT_API_URL/oauth/token" \
    -H 'Content-Type: application/json' \
    -d "{\"client_id\": \"$AUTH0_SV_MANAGEMENT_API_CLIENT_ID\",\"client_secret\": \"$AUTH0_SV_MANAGEMENT_API_CLIENT_SECRET\",\"audience\": \"$AUTH0_SV_MANAGEMENT_API_URL/api/v2/\",\"grant_type\":\"client_credentials\"}" | jq -r '.access_token')

AUTH0_CN_ALL_APPS="$(curl -sSL --fail-with-body -H "Authorization: Bearer $AUTH0_CN_MANAGEMENT_TOKEN" ${AUTH0_CN_MANAGEMENT_API_URL}/api/v2/clients)"
AUTH0_SV_ALL_APPS="$(curl -sSL --fail-with-body -H "Authorization: Bearer $AUTH0_SV_MANAGEMENT_TOKEN" ${AUTH0_SV_MANAGEMENT_API_URL}/api/v2/clients)"

client_ids=(
    [1]='OBpJ9oTyOLuAKF0H2hhzdSFUICt0diIn'
    [2]='rv4bllgKWAiW9tBtdvURMdHW42MAXghz'
    [3]='SeG68w0ubtLQ1dEMDOs4YKPRTyMMdDLk'
    [4]='CqKgSbH54dqBT7V1JbnCxb6TfMN8I1cN'
)

function approve_sv() {
    ALL_APPS="$1"
    MANAGEMENT_URL="$2"
    CLIENT_ID="$3"
    PREFIX="$4"
    AUDIENCE="$5"

    client_secret=$(jq -r ".[] | select(.client_id==\"$CLIENT_ID\") | .client_secret" <<< "$ALL_APPS")

    echo "Querying token to access SV $PREFIX API"

    sv_token=$(curl -sSL --fail-with-body "$MANAGEMENT_URL/oauth/token" \
      -H 'content-type: application/json' \
      -d "{\"client_id\":\"$CLIENT_ID\",\"client_secret\":\"$client_secret\",\"audience\":\"$AUDIENCE\",\"grant_type\":\"client_credentials\"}" | jq '.access_token' -r)

    echo "Sending new key to SV $PREFIX API"

    curl -sSL --fail-with-body "https://$PREFIX.$GCP_CLUSTER_BASENAME.network.canton.global/api/sv/admin/sv/identity/approve" -d "{\"candidateName\": \"$candidateName\", \"candidateKey\": \"$candidateKey\"}" -H "Authorization: Bearer $sv_token" -H "Content-Type: application/json"
}

for i in 1 2 3 4
do
    client_id="${client_ids[$i]}"
    approve_sv "$AUTH0_CN_ALL_APPS" "$AUTH0_CN_MANAGEMENT_API_URL" "$client_id" "sv.sv-$i.svc" "https://canton.network.global"
done

approve_sv "$AUTH0_SV_ALL_APPS" "$AUTH0_SV_MANAGEMENT_API_URL" "bUfFRpl2tEfZBB7wzIo9iRNGTj8wMeIn" "sv.sv.svc" "https://sv.example.com/api"
