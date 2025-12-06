// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as k8s from '@pulumi/kubernetes';
import * as pulumi from '@pulumi/pulumi';
import { spliceEnvConfig } from '@lfdecentralizedtrust/splice-pulumi-common/src/config/envConfig';

const kubectlVersion = spliceEnvConfig.requireEnv('KUBECTL_VERSION');
const cronJobName = 'gc-pod-reaper-job';
const targetNamespace = 'gc-pod-reaper';
const serviceAccountName = 'gc-pod-reaper-service-account';
const reaperImage = 'rancher/kubectl:' + kubectlVersion;
const schedule = '0 3 * * *'; // Run once daily at 03:00 AM UTC

const deleteBadPodsCommand = [
  '/bin/sh',
  '-c',
  `
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
      name: targetNamespace,
      labels: {
        'app.kubernetes.io/name': 'gc-pod-reaper',
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
                initContainers: [
                  {
                    name: 'install-dependencies',
                    image: reaperImage,
                    command: ['/bin/sh', '-c', 'apk add --no-cache jq'],
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
