#! /bin/env bash

set -euo pipefail

# Check if argument is provided
if [ "$#" -ne 1 ]; then
    echo "Usage: <version>"
    exit 1
fi

version=$1
crd_location="https://raw.githubusercontent.com/prometheus-operator/prometheus-operator/v${version}/example/prometheus-operator-crd"

echo "Updating prometheus CRDs to $version"

kubectl apply --server-side --force-conflicts -f "${crd_location}/monitoring.coreos.com_alertmanagerconfigs.yaml"
kubectl apply --server-side --force-conflicts -f "${crd_location}/monitoring.coreos.com_alertmanagers.yaml"
kubectl apply --server-side --force-conflicts -f "${crd_location}/monitoring.coreos.com_podmonitors.yaml"
kubectl apply --server-side --force-conflicts -f "${crd_location}/monitoring.coreos.com_probes.yaml"
kubectl apply --server-side --force-conflicts -f "${crd_location}/monitoring.coreos.com_prometheuses.yaml"
kubectl apply --server-side --force-conflicts -f "${crd_location}/monitoring.coreos.com_prometheusrules.yaml"
kubectl apply --server-side --force-conflicts -f "${crd_location}/monitoring.coreos.com_servicemonitors.yaml"
kubectl apply --server-side --force-conflicts -f "${crd_location}/monitoring.coreos.com_thanosrulers.yaml"
