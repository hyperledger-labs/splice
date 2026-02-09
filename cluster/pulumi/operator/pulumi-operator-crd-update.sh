#! /bin/env bash

# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

set -euo pipefail

# Check if argument is provided
if [ "$#" -ne 1 ]; then
    echo "Usage: <version>"
    exit 1
fi

version=$1
crd_location="https://raw.githubusercontent.com/pulumi/pulumi-kubernetes-operator/v${version}/deploy/crds"

echo "Updating Pulumi Kubernetes Operator CRDs to $version"

kubectl apply --server-side --force-conflicts -f "${crd_location}/pulumi.com_stacks.yaml"
kubectl apply --server-side --force-conflicts -f "${crd_location}/pulumi.com_programs.yaml"
kubectl apply --server-side --force-conflicts -f "${crd_location}/auto.pulumi.com_updates.yaml"
kubectl apply --server-side --force-conflicts -f "${crd_location}/auto.pulumi.com_workspaces.yaml"
