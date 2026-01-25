// Copyright (c) 2026 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as k8s from '@pulumi/kubernetes';
import * as pulumi from '@pulumi/pulumi';
import {
  generatePortSequence,
  numNodesPerInstance,
} from '@lfdecentralizedtrust/splice-pulumi-common';

import { multiValidatorConfig } from './config';
import { BaseMultiNodeArgs, MultiNodeDeployment } from './multiNodeDeployment';

export class MultiParticipant extends MultiNodeDeployment {
  constructor(name: string, args: BaseMultiNodeArgs, opts?: pulumi.ComponentResourceOptions) {
    const ports = generatePortSequence(5000, numNodesPerInstance, [
      { name: 'lg', id: 1 },
      { name: 'adm', id: 2 },
    ]);

    super(
      name,
      {
        ...args,
        imageName: 'multi-participant',
        container: {
          env: [
            {
              name: 'CANTON_PARTICIPANT_POSTGRES_SERVER',
              value: args.postgres.host,
            },
            {
              name: 'CANTON_PARTICIPANT_POSTGRES_DB',
              value: args.postgres.db,
            },
            {
              name: 'CANTON_PARTICIPANT_POSTGRES_SCHEMA',
              value: args.postgres.schema,
            },
            {
              name: 'CANTON_PARTICIPANT_POSTGRES_PASSWORD',
              valueFrom: {
                secretKeyRef: args.postgres.secret,
              },
            },
            {
              name: 'LOG_LEVEL_CANTON',
              value: multiValidatorConfig?.logLevel,
            },
            {
              name: 'LOG_LEVEL_STDOUT',
              value: multiValidatorConfig?.logLevel,
            },
          ],
          ports: ports.map(port => ({
            name: port.name,
            containerPort: port.port,
            protocol: 'TCP',
          })),
          resources: multiValidatorConfig?.resources
            ?.participant as k8s.types.input.core.v1.ResourceRequirements,
          readinessProbe: {
            grpc: {
              port: 5061,
            },
            initialDelaySeconds: 5,
            periodSeconds: 5,
            failureThreshold: 3,
            timeoutSeconds: 10,
          },
          livenessProbe: {
            grpc: {
              port: 5061,
              service: 'liveness',
            },
            initialDelaySeconds: 60,
            periodSeconds: 60,
            failureThreshold: 5,
            timeoutSeconds: 10,
          },
        },
        serviceSpec: { ports },
      },
      opts,
      /*
       *   https://docs.oracle.com/en/java/javase/11/gctuning/garbage-first-garbage-collector-tuning.html
       *
       * G1UseAdaptiveIHOP - turn off adaptive IHOP based on application behavior and set a low value for InitiatingHeapOccupancyPercent (IHOP),
       * as we expect in most scenarios our heap usage to be quite low.
       *
       * G1MixedGCLiveThresholdPercent - lower the threshold for mixed GCs to trigger mixed GCs more frequently (old gen collection).
       * G1HeapWastePercent - lower the amount of heap space we're willing to waste as it's based on total heap and in most scenario we expect low heap usage
       * GCTimeRatio - dedicate more cpi time to GC compared to default usage to keep heap low (~16% vs default 8%)
       * */
      '-XX:+UnlockExperimentalVMOptions -XX:-G1UseAdaptiveIHOP -XX:G1MixedGCLiveThresholdPercent=12 -XX:G1HeapWastePercent=2 -XX:InitiatingHeapOccupancyPercent=10 -XX:GCTimeRatio=6',
      multiValidatorConfig?.extraParticipantEnvVars
    );
  }
}
