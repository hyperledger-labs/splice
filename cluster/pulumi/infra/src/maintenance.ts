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
    LOG_FILE="/tmp/pod-reaper-$(date +%Y%m%d%H%M%S).log";
    echo "--- $(date) Starting Pod Reaper ---" >> $LOG_FILE;
    echo "Processing pods for deletion..." >> $LOG_FILE;

    TARGET_PODS=$(
      kubectl get pods -A -o json | \\
      jq -r '.items[] |
          # Check if the Pod status is "ContainerStatusUnknown"
          (select(.status.phase == "Unknown" and .status.reason == "ContainerStatusUnknown") |
              .metadata.namespace + " " + .metadata.name) //

          # Check for Terminated container with reason "Error" and exit code 137
          (select(.status.containerStatuses[]?.state.terminated? |
              .reason == "Error" and .exitCode == 137) |
              .metadata.namespace + " " + .metadata.name)'
    );

    echo "Found pods for deletion:" >> $LOG_FILE;
    echo "$TARGET_PODS" >> $LOG_FILE;

    if [ -z "$TARGET_PODS" ]; then
        echo "No target pods found. Exiting." >> $LOG_FILE;
        exit 0
    fi

    echo "--- Deleting Target Pods ---" >> $LOG_FILE;

    echo "$TARGET_PODS" | while read -r NAMESPACE NAME; do
        if [ -n "$NAMESPACE" ] && [ -n "$NAME" ]; then
            echo "Deleting pod $NAMESPACE/$NAME" >> $LOG_FILE;
            kubectl delete pod -n "$NAMESPACE" "$NAME" >> $LOG_FILE 2>&1
            if [ $? -eq 0 ]; then
                echo "Successfully deleted $NAMESPACE/$NAME" >> $LOG_FILE;
            else
                echo "Failed to delete $NAMESPACE/$NAME" >> $LOG_FILE;
            fi
        fi
    done

    echo "--- $(date) Pod Reaper Finished ---" >> $LOG_FILE;
    true
  `,
];

export function deployGCPodReaper(
  name: string,
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
        annotations: {
          'pulumi.com/description': 'Scheduled job to automatically clean up error/stuck pods',
        },
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
