// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as k8s from '@pulumi/kubernetes';
import * as pulumi from '@pulumi/pulumi';

const cronJobName = 'gc-pod-reaper-job';
const targetNamespace = 'gc-pod-reaper';
const serviceAccountName = 'gc-pod-reaper-service-account';
const reaperImage = 'bitnami/kubectl:latest';
const schedule = '0 3 * * *'; // Run once daily at 03:00 AM UTC

const deleteBadPodsCommand = [
  '/bin/sh',
  '-c',
  `kubectl get pods -A --field-selector=status.phase!=Running -o custom-columns=NAMESPACE:.metadata.namespace,NAME:.metadata.name,STATUS:.status.phase,REASON:.status.containerStatuses[*].state.waiting.reason | \\
  grep -E "Error|Unknown|CrashLoopBackOff|Terminating" | \\
  awk '{print $1, $2}' | \\
  xargs -n 2 sh -c 'kubectl delete pod -n "$0" "$1"' _ || true`,
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
