#!/usr/bin/env bash

# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

set -eou pipefail

# We could also dynamically do the mapping of emails to user ids in the pulumi config.
# However, there are some attacks based on doing that. So instead
# we check in the generated mapping and update it on demand to
# avoid accidentally changing the sub.
# See https://trufflesecurity.com/blog/millions-at-risk-due-to-google-s-oauth-flaw

tenants=(canton-network-dev.us.auth0.com canton-network-sv-test.us.auth0.com canton-network-mainnet.us.auth0.com)
users="$(yq < cluster/user-configs/sv-users.yaml .user_emails -o json)"
for tenant in "${tenants[@]}"
do
  all_auth0_users="$(auth0 users search -n 1000 --query digitalasset.com --tenant "$tenant" --json)"
  jq -n --argjson auth0_users "$all_auth0_users" --argjson approved_users "$users" \
     '$auth0_users | map(select(. as $user | $approved_users | index($user.email)))' > \
    "$REPO_ROOT/cluster/user-configs/$tenant.json"
done
