// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as k8s from '@pulumi/kubernetes';
import * as pulumi from '@pulumi/pulumi';
import { spliceEnvConfig } from '@lfdecentralizedtrust/splice-pulumi-common/src/config/envConfig';

const kubectlVersion = spliceEnvConfig.requireEnv('KUBECTL_VERSION');
const cronJobName = 'gc-pod-reaper-job';
const reaperNamespace = 'gc-pod-reaper';
const serviceAccountName = 'gc-pod-reaper-service-account';
const reaperImage = 'rancher/kubectl:' + kubectlVersion;
const schedule = '* * * * *'; // Run once daily at 03:00 AM UTC

const deleteBadPodsCommand = [
  '/bin/sh',
  '-c',
  `
    apk add --no-cache jq;

    if [ $? -ne 0 ]; then
        echo "Error: Failed to install jq. Exiting.";
        exit 1
    fi

    echo "--- $(date) Starting Pod Reaper ---";

    TARGET_NAMESPACES_LIST=$(echo "$TARGET_NAMESPACES" | tr ',' ' ');
    echo "Targeting namespaces: $TARGET_NAMESPACES_LIST";

    if [ -z "$TARGET_NAMESPACES_LIST" ]; then
        echo "Error: No target namespaces provided. Exiting.";
        exit 1
    fi

    echo "--- Starting Cleanup Loop ---";

    for NAMESPACE in $TARGET_NAMESPACES_LIST; do
        echo "Processing namespace: $NAMESPACE";

        BAD_PODS=$(
          kubectl get pods -n "$NAMESPACE" -o json | \\
          jq -r '.items[] |
              (select(.status.phase == "Unknown" and .status.reason == "ContainerStatusUnknown") |
                  .metadata.name) //

              (select(.status.containerStatuses[]?.state.terminated? |
                  .reason == "Error" and .exitCode == 137) |
                  .metadata.name)'
        );

        if [ -z "$BAD_PODS" ]; then
            echo "No bad pods found in $NAMESPACE. Skipping.";
            continue
        fi

        echo "Found bad pods in $NAMESPACE: $BAD_PODS";

        for POD_NAME in $BAD_PODS; do
            echo "Attempting to delete pod $NAMESPACE/$POD_NAME";
            kubectl delete pod -n "$NAMESPACE" "$POD_NAME"
            if [ $? -eq 0 ]; then
                echo "Successfully deleted $NAMESPACE/$POD_NAME";
            else
                echo "Failed to delete $NAMESPACE/$POD_NAME";
            fi
        done
        echo "Finished cleanup for $NAMESPACE.";
        echo "---"
    done

    echo "--- $(date) Pod Reaper Finished ---";
    true
  `,
];

export function deployGCPodReaper(
  name: string,
  targetNamespaces: string[],
  opts?: pulumi.ComponentResourceOptions
): k8s.batch.v1.CronJob {
  const ns = new k8s.core.v1.Namespace(name, {
    metadata: {
      name: reaperNamespace,
      labels: {
        'app.kubernetes.io/name': reaperNamespace,
      },
    },
  });

  const targetNamespacesEnv = targetNamespaces.join(',');

  new k8s.core.v1.ServiceAccount(
    serviceAccountName,
    {
      metadata: {
        name: serviceAccountName,
        namespace: ns.metadata.name,
      },
    },
    { parent: ns }
  );

  return new k8s.batch.v1.CronJob(
    cronJobName,
    {
      metadata: {
        name: cronJobName,
        namespace: ns.metadata.name,
      },
      spec: {
        schedule: schedule,
        concurrencyPolicy: 'Forbid',
        successfulJobsHistoryLimit: 2,
        failedJobsHistoryLimit: 2,
        jobTemplate: {
          metadata: {
            labels: {
              app: 'gc-pod-reaper',
            },
          },
          spec: {
            template: {
              spec: {
                serviceAccountName: serviceAccountName,
                restartPolicy: 'OnFailure',
                // Nodes in this GKE cluster are configured with Taints (cn_infra, cn_apps,
                // gke-managed-components) that prevent standard Pods from being scheduled.
                // These Tolerations allow the gc-pod-reaper-job to run on all available
                // nodes, ensuring the cleanup task can execute regardless of node role.
                tolerations: [
                  {
                    key: 'cn_infra',
                    operator: 'Equal', // Must be 'Equal' because the Taint has a value (`true`)
                    value: 'true', // Must match the Taint's value
                    effect: 'NoSchedule', // Must match the Taint's effect
                  },
                  {
                    key: 'components.gke.io/gke-managed-components',
                    operator: 'Equal', // Must be 'Equal'
                    value: 'true', // Must match the Taint's value
                    effect: 'NoSchedule',
                  },
                  {
                    key: 'cn_apps',
                    operator: 'Equal', // Must be 'Equal'
                    value: 'true', // Must match the Taint's value
                    effect: 'NoSchedule',
                  },
                ],
                containers: [
                  {
                    name: cronJobName,
                    image: reaperImage,
                    imagePullPolicy: 'IfNotPresent',
                    command: deleteBadPodsCommand,
                    env: [
                      {
                        name: 'TARGET_NAMESPACES',
                        value: targetNamespacesEnv,
                      },
                    ],
                  },
                ],
              },
            },
          },
        },
      },
    },
    { parent: ns, ...opts }
  );
}
