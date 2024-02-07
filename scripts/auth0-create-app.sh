#!/usr/bin/env bash
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

    existingApp=$(echo "$list" | grep "$appName")

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

function urlList() {
    local svId="$1"

    echo "https://wallet.${svId}.svc.scratcha.network.canton.global, https://sv.${svId}.svc.scratcha.network.canton.global, https://directory.${svId}.svc.scratcha.network.canton.global, https://cns.${svId}.svc.scratcha.network.canton.global, https://wallet.${svId}.svc.scratchb.network.canton.global, https://sv.${svId}.svc.scratchb.network.canton.global, https://directory.${svId}.svc.scratchb.network.canton.global, https://cns.${svId}.svc.scratchb.network.canton.global, https://wallet.${svId}.svc.scratchc.network.canton.global, https://sv.${svId}.svc.scratchc.network.canton.global, https://directory.${svId}.svc.scratchc.network.canton.global, https://cns.${svId}.svc.scratchc.network.canton.global, https://wallet.${svId}.svc.scratchd.network.canton.global, https://sv.${svId}.svc.scratchd.network.canton.global, https://directory.${svId}.svc.scratchd.network.canton.global, https://cns.${svId}.svc.scratchd.network.canton.global, https://wallet.${svId}.svc.scratche.network.canton.global, https://sv.${svId}.svc.scratche.network.canton.global, https://directory.${svId}.svc.scratche.network.canton.global, https://cns.${svId}.svc.scratche.network.canton.global, https://wallet.${svId}.svc.scratchf.network.canton.global, https://sv.${svId}.svc.scratchf.network.canton.global, https://directory.${svId}.svc.scratchf.network.canton.global, https://cns.${svId}.svc.scratchf.network.canton.global, https://wallet.${svId}.svc.scratchg.network.canton.global, https://sv.${svId}.svc.scratchg.network.canton.global, https://directory.${svId}.svc.scratchg.network.canton.global, https://cns.${svId}.svc.scratchg.network.canton.global, https://wallet.${svId}.svc.dev.network.canton.global, https://sv.${svId}.svc.dev.network.canton.global, https://directory.${svId}.svc.dev.network.canton.global, https://cns.${svId}.svc.dev.network.canton.global, https://wallet.${svId}.svc.staging.network.canton.global, https://sv.${svId}.svc.staging.network.canton.global, https://directory.${svId}.svc.staging.network.canton.global, https://cns.${svId}.svc.staging.network.canton.global, https://wallet.${svId}.svc.test.network.canton.global, https://sv.${svId}.svc.test.network.canton.global, https://directory.${svId}.svc.test.network.canton.global, https://cns.${svId}.svc.test.network.canton.global, https://wallet.${svId}.svc.test-preview.network.canton.global, https://sv.${svId}.svc.test-preview.network.canton.global, https://directory.${svId}.svc.test-preview.network.canton.global, https://cns.${svId}.svc.test-preview.network.canton.global, https://wallet.${svId}.svc.cidaily.network.canton.global, https://sv.${svId}.svc.cidaily.network.canton.global, https://directory.${svId}.svc.cidaily.network.canton.global, https://cns.${svId}.svc.cidaily.network.canton.global, https://wallet.${svId}.svc.cimain.network.canton.global, https://wallet.${svId}.svc.cidaily-testnet.network.canton.global, https://sv.${svId}.svc.cimain.network.canton.global, https://sv.${svId}.svc.cidaily-testnet.network.canton.global, https://directory.${svId}.svc.cimain.network.canton.global, https://cns.${svId}.svc.cimain.network.canton.global, https://directory.${svId}.svc.cidaily-testnet.network.canton.global, https://cns.${svId}.svc.cidaily-testnet.network.canton.global, https://wallet.${svId}.svc.cilr.network.canton.global, https://sv.${svId}.svc.cilr.network.canton.global, https://directory.${svId}.svc.cilr.network.canton.global, https://cns.${svId}.svc.cilr.network.canton.global, https://wallet.${svId}.svc.pilot.network.canton.global, https://sv.${svId}.svc.pilot.network.canton.global, https://directory.${svId}.svc.pilot.network.canton.global, https://cns.${svId}.svc.pilot.network.canton.global"
}

function createSvFrontendApp() {
    local svId="$1"
    local appName="$2"
    local list="$3"

    existingApp=$(echo "$list" | grep "$appName")

    if [ -z "$existingApp" ]; then
        echo "App does not exist, creating"

        app=$(auth0 apps create \
            --name "$appName" \
            --type "spa" \
            --callbacks "$(urlList "$svId")" \
            --origins "$(urlList "$svId")" \
            --logout-urls "$(urlList "$svId")" \
            --web-origins "$(urlList "$svId")" \
            --json)

        echo "App name: $(echo "$app" | jq -r '.name') exists"
        echo "Client ID: $(echo "$app" | jq -r '.client_id')"
        return
    else
        echo "$existingApp"
    fi
}

function createSvAdminUser() {
    local email="$1"
    list="$(auth0 users search --query 'email:'"$email"'')"

    existingUser=$(echo "$list" | grep "$email")

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

    list="$(auth0 apps list)"

    createSvBackendApp "$svId" "SV$svId backend" "$list"
    createSvBackendApp "$svId" "SV$svId validator backend" "$list"
    createSvFrontendApp "$svId" "SV$svId frontends" "$list"

    createSvAdminUser "admin@sv$svId-dev.com"
}

createSvApps "6" "SV6"
createSvApps "7" "SV7"
createSvApps "8" "SV8"
createSvApps "9" "SV9"
