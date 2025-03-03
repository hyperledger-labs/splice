#!/usr/bin/env bash

# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

set -euo pipefail

# shellcheck disable=SC1091
source "${TOOLS_LIB}/libcli.source"

if [ $# -ne 3 ]; then
  _error "Usage: $0 <snapshot_name> <namespace> <target_dir>"
  exit 1
fi

snapshot_name=$1
namespace=$2
target_dir=$3

run_id=$(echo "$snapshot_name" | grep -oe "[0-9]*$")
STORAGE_CLASS_NAME=standard-rwo

vs=$(kubectl get volumesnapshot -n "$namespace" -o json "$snapshot_name")
restore_size=$(echo "$vs" | jq -r .status.restoreSize)
# Make all names unique so they don't conflict if we run multiple instances of this script in parallel
pvc_name="pvc-backup-${run_id}"
job_name="backup-${run_id}"
secret_name="gcp-credentials-${run_id}"

function cleanup() {
  kubectl delete job -n "$namespace" "$job_name" || true
  kubectl delete pvc -n "$namespace" "$pvc_name" || true
  kubectl delete secret -n "$namespace" "$secret_name" || true
}
trap cleanup EXIT

kubectl create secret generic -n "$namespace" "$secret_name" --from-literal=gcp-credentials.json="$GOOGLE_CREDENTIALS"

kubectl apply -n "$namespace" -f - <<EOF
apiVersion: v1
kind: PersistentVolumeClaim
metadata:
  name: "$pvc_name"
spec:
  accessModes:
    - ReadWriteOnce
  resources:
    requests:
      storage: "$restore_size"
  storageClassName: "$STORAGE_CLASS_NAME"
  volumeMode: Filesystem
  dataSource:
    name: "$snapshot_name"
    kind: VolumeSnapshot
    apiGroup: snapshot.storage.k8s.io
EOF

kubectl apply -n "$namespace" -f - <<EOF
apiVersion: batch/v1
kind: Job
metadata:
  name: ${job_name}
spec:
  template:
    metadata:
      labels:
        sidecar.istio.io/inject: "false"
    spec:
      volumes:
        - name: restore-data
          persistentVolumeClaim:
            claimName: "$pvc_name"
        - name: gcp-credentials
          secret:
            secretName: "$secret_name"
        - name: work
          ephemeral:
            volumeClaimTemplate:
              spec:
                accessModes: [ "ReadWriteOnce" ]
                storageClassName: "$STORAGE_CLASS_NAME"
                resources:
                  requests:
                    storage: $restore_size
      containers:
      - name: backup
        image: gcr.io/google.com/cloudsdktool/google-cloud-cli:489.0.0-stable
        volumeMounts:
          - mountPath: "/data"
            name: restore-data
          - mountPath: "/root/gcloud"
            name: gcp-credentials
            readOnly: true
          - mountPath: "/work"
            name: work
        command: ["/bin/sh"]
        args: ["-c", "gcloud auth activate-service-account --key-file /root/gcloud/gcp-credentials.json && tar -C /data -czvf /work/backup.tar.gz . && gsutil cp /work/backup.tar.gz ${target_dir}/cometbft/cometbft.tar.gz"]
        env:
          - name: GOOGLE_APPLICATION_CREDENTIALS
            value: "/root/gcloud/gcp-credentials.json"
      tolerations:
      - key: "cn_apps"
        operator: "Exists"
        effect: "NoSchedule"
      restartPolicy: OnFailure
EOF
# would be better to use cn_infra, but that nodepool is disabled on mainnet-old, so for now using cn_apps for mainnet-old

set +e

retval_complete=1
retval_failed=1
i=0
while [[ $retval_complete -ne 0 ]] && [[ $retval_failed -ne 0 ]]; do
  _info "Waiting for PVC backup job to complete..."
  sleep 10
  kubectl wait --for=condition=failed -n "$namespace" "job/backup-${run_id}" --timeout=0 > /dev/null 2>&1
  retval_failed=$?
  kubectl wait --for=condition=complete -n "$namespace" "job/backup-${run_id}" --timeout=0 > /dev/null 2>&1
  retval_complete=$?
  if [ $i -gt 1080 ]; then
    _error "PVC backup job did not complete after 3 hours"
    exit 1
  fi
  i=$((i + 1))
done

set -e

if [ $retval_failed -eq 0 ]; then
    echo "Job failed. Please check logs."
    exit 1
fi
