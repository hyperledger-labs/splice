#!/usr/bin/env bash

# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

set -euo pipefail
# https://istio.io/latest/docs/setup/upgrade/helm/#canary-upgrade-recommended
for crd in $(kubectl get crds -l chart=istio -o name && kubectl get crds -l app.kubernetes.io/part-of=istio -o name)
do
   kubectl label "$crd" "app.kubernetes.io/managed-by=Helm"
   kubectl annotate "$crd" "meta.helm.sh/release-name=istio-base"
   kubectl annotate "$crd" "meta.helm.sh/release-namespace=istio-system"
done
