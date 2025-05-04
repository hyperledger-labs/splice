#!/usr/bin/env bash

# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

set -eou pipefail

oci_helm_repo="${OCI_DEV_HELM_REGISTRY}"
chart=$1

push_helm_chart() {
  source=$1
  echo "Publishing helm chart ${source} to ${oci_helm_repo}"
  helm push "${source}" "${oci_helm_repo}"
}

publish () {
  source=$1
  if [[ "$chart" == *-dirty.tgz ]]; then
    push_helm_chart "${source}"
  else
    chart_file=$(basename "${source}")

    chart_name="${chart_file%-[0-9]*.[0-9]*.[0-9]*-*}"
    chart_name="${chart_name%.tgz}"

    version="${chart_file#"$chart_name"-}"
    version="${version%.tgz}"

    if helm show chart "${oci_helm_repo}/${chart_name}" --version "${version}" > /dev/null 2>&1; then
      echo "Helm chart already exists: ${oci_helm_repo}/${chart_name}:${version}"
    else
      push_helm_chart "${source}"
    fi
 fi
}

publish "${chart}"
