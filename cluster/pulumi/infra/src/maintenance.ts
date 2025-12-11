// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as k8s from '@pulumi/kubernetes';
import * as pulumi from '@pulumi/pulumi';
import { DOCKER_REPO } from '@lfdecentralizedtrust/splice-pulumi-common';
import { spliceEnvConfig } from '@lfdecentralizedtrust/splice-pulumi-common/src/config/envConfig';
import { Version } from '@lfdecentralizedtrust/splice-pulumi-multi-validator/version';

import { infraAffinityAndTolerations } from '../../common';

const kubectlVersion = spliceEnvConfig.requireEnv('KUBECTL_VERSION');
const cronJobName = 'gc-pod-reaper-job';
const reaperNamespace = 'gc-pod-reaper';
const serviceAccountName = 'gc-pod-reaper-service-account';
// Rancher/Official K8s images failed (exec: no such file) as they lack /bin/ash shell or anything useful.
// Bitnami has moved most images and Helm charts behind a paywall

const schedule = '*/2 * * * *'; // Run once every 2 minutes

const deleteBadPodsCommand = [
  '/bin/bash',
  '-c',
  `
    apt-get update &&
    apt-get install -y jq &&
    curl -LO https://dl.k8s.io/release/${kubectlVersion}/bin/linux/amd64/kubectl &&
    install -o root -g root -m 0755 kubectl /usr/local/bin/kubectl;


    if [ $? -ne 0 ] || ! command-v kubectl >/dev/null 2>&1 || ! command -v jq >/dev/null 2>&1; then
        echo "Error: Failed to install kubectl or jq. Exiting.";
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

  targetNamespaces.forEach(namespace => {
    const podManagementRole = new k8s.rbac.v1.Role(
      namespace + '-gc-pod-reaper-role',
      {
        metadata: {
          name: namespace + '-gc-pod-reaper-role',
          namespace: namespace,
        },
        rules: [
          {
            apiGroups: [''], // Core API group for Pods
            resources: ['pods'],
            verbs: ['list', 'create', 'delete', 'update'],
          },
        ],
      },
      { parent: ns }
    );

    new k8s.rbac.v1.RoleBinding(
      namespace + '-gc-pod-reaper-pod-manager-binding',
      {
        metadata: {
          name: namespace + 'gc-pod-reaper-pod-manager-binding',
          namespace: namespace,
        },
        subjects: [
          {
            kind: 'ServiceAccount',
            name: serviceAccountName,
            namespace: 'gc-pod-reaper',
          },
        ],
        roleRef: {
          kind: 'Role',
          name: podManagementRole.metadata.name,
          apiGroup: 'rbac.authorization.k8s.io',
        },
      },
      { parent: podManagementRole, dependsOn: [podManagementRole] }
    );
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
                ...infraAffinityAndTolerations,
                containers: [
                  {
                    name: cronJobName,
                    image: `${DOCKER_REPO}/splice-debug:${Version}`,
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
