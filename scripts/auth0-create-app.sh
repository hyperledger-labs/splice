#!/usr/bin/env bash
# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

set -euo pipefail

## Quick script to spin up new SVs in Auth0

auth0 login \
    --client-id "${AUTH0_CN_MANAGEMENT_API_CLIENT_ID}" \
    --client-secret "${AUTH0_CN_MANAGEMENT_API_CLIENT_SECRET}" \
    --domain "canton-network-dev.us.auth0.com"

# Create an Auth0 application
function createSvBackendApp() {
    local svId="$1"
    local appName="$2"
    local list="$3"

    existingApp=$(echo "$list" | grep "$appName" || echo "")

    if [ -z "$existingApp" ]; then
        echo "App does not exist, creating"

        app=$(auth0 apps create \
            --name "$appName" \
            --type "m2m" \
            --json)

        echo "App: $app"
        client_id=$(echo "$app" | jq -r '.client_id')

        auth0 api post "client-grants" --data '{"scope": ["daml_ledger_api"], "audience":"https://canton.network.global", "client_id":"'"$client_id"'"}'
        return
    else
        echo "$existingApp"
    fi
}

function createSvAdminUser() {
    local email="$1"
    list="$(auth0 users search --query 'email:'"$email"'')"

    existingUser=$(echo "$list" | grep "$email" || echo "")

    if [ -z "$SV_DEV_NET_WEB_UI_PASSWORD" ]; then
        echo "ERROR: SV_DEV_NET_WEB_UI_PASSWORD is not set. Add it to your .envrc.private file"
        exit 1
    fi

    if [ -z "$existingUser" ]; then
        echo "User does not exist, creating"

        user=$(auth0 users create \
            --email "$email" \
            --name "$email" \
            --username "$email" \
            --password "$SV_DEV_NET_WEB_UI_PASSWORD" \
            --connection-name "Username-Password-Authentication" \
            --json)

        echo "User: $user"
        return
    else
        echo "$existingUser"
    fi
}

function createSvApps() {
    local svId="$1"

    list="$(auth0 apps list -n 1000)"


    createSvBackendApp "$svId" "SV$svId backend" "$list"
    createSvBackendApp "$svId" "SV$svId validator backend" "$list"

    createSvAdminUser "admin@sv$svId-dev.com"
}

createSvApps "6" "SV6"
createSvApps "7" "SV7"
createSvApps "8" "SV8"
createSvApps "9" "SV9"
createSvApps "10" "SV10"
createSvApps "11" "SV11"
createSvApps "12" "SV12"
createSvApps "13" "SV13"
createSvApps "14" "SV14"
createSvApps "15" "SV15"
createSvApps "16" "SV16"
