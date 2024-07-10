#!/usr/bin/env bash
# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

set -eou pipefail

if [ -z "${GCP_CLUSTER_BASENAME-}" ]; then
    >&2 echo "The env var GCP_CLUSTER_BASENAME must be set."
    exit 1
fi

count=0

# Prevent the -e flag from bypassing the retry logic in this loop
set +e
while [ "${count}" -lt 10 ]; do
    if cluster_version=$(curl -sL "http://${GCP_CLUSTER_HOSTNAME}/version" --connect-timeout 2); then
        if [[ -n $cluster_version ]]; then
            break
        else
            >&2 echo "Empty cluster version retrieved. Retry count $count"
        fi
    else
        >&2 echo "Failed to retrieve cluster version. Check that you are connected to the VPN."
    fi
    ((count++))
    sleep 3
done
set -e

if [ "${count}" -eq 10 ]; then
    >&2 echo "Could not retrieve cluster version"
    exit 1
fi

if [[ ${cluster_version} =~ ^[0-9.]*-snapshot[0-9.]*v[0-9a-z]* ]]; then
    echo "${cluster_version}"
else
    >&2 echo "Unexpected cluster version format: ${cluster_version}"
    exit 1
fi
