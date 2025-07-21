// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as pulumi from '@pulumi/pulumi';
import {
  DecentralizedSynchronizerMigrationConfig,
  daContactPoint,
  generatePortSequence,
  numNodesPerInstance,
  DecentralizedSynchronizerUpgradeConfig,
} from 'splice-pulumi-common';

import { multiValidatorConfig } from './config';
import { BaseMultiNodeArgs, MultiNodeDeployment } from './multiNodeDeployment';

interface MultiValidatorArgs extends BaseMultiNodeArgs {
  participant: {
    address: pulumi.Output<string>;
  };
}

const decentralizedSynchronizerUpgradeConfig: DecentralizedSynchronizerMigrationConfig =
  DecentralizedSynchronizerUpgradeConfig;

export class MultiValidator extends MultiNodeDeployment {
  constructor(name: string, args: MultiValidatorArgs, opts?: pulumi.ComponentResourceOptions) {
    const ports = generatePortSequence(5000, numNodesPerInstance, [{ name: 'val', id: 3 }]);

    super(
      name,
      {
        ...args,
        imageName: 'multi-validator',
        container: {
          env: [
            {
              name: 'SPLICE_APP_DEVNET',
              value: '1',
            },
            {
              name: 'SPLICE_APP_VALIDATOR_PARTICIPANT_ADDRESS',
              value: pulumi.interpolate`${args.participant.address}`,
            },
            {
              name: 'SPLICE_APP_VALIDATOR_SCAN_URL',
              value: `http://scan-app.sv-1:5012`,
            },
            {
              name: 'SPLICE_APP_VALIDATOR_LEDGER_API_AUTH_AUDIENCE',
              value: 'https://canton.network.global',
            },
            {
              name: 'SPLICE_APP_VALIDATOR_AUTH_AUDIENCE',
              value: 'https://canton.network.global',
            },
            {
              name: 'SPLICE_APP_VALIDATOR_SV_SPONSOR_ADDRESS',
              value: `http://sv-app.sv-1:5014`,
            },
            {
              name: 'SPLICE_APP_POSTGRES_DATABASE_NAME',
              value: args.postgres.db,
            },
            {
              name: 'SPLICE_APP_POSTGRES_SCHEMA',
              value: args.postgres.schema,
            },
            {
              name: 'SPLICE_APP_POSTGRES_HOST',
              value: args.postgres.host,
            },
            {
              name: 'SPLICE_APP_POSTGRES_PORT',
              value: args.postgres.port,
            },
            {
              name: 'SPLICE_APP_POSTGRES_USER',
              value: 'cnadmin',
            },
            {
              name: 'SPLICE_APP_POSTGRES_PASSWORD',
              valueFrom: {
                secretKeyRef: args.postgres.secret,
              },
            },
            {
              name: 'SPLICE_APP_VALIDATOR_MIGRATION_ID',
              value: decentralizedSynchronizerUpgradeConfig.active.id.toString(),
            },
            {
              name: 'SPLICE_APP_CONTACT_POINT',
              value: daContactPoint,
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
          livenessProbe: {
            exec: {
              command: ['/bin/bash', '/app/health-check.sh', 'api/validator/livez'],
            },
            initialDelaySeconds: 60,
            periodSeconds: 60,
            failureThreshold: 5,
            timeoutSeconds: 10,
          },
          readinessProbe: {
            exec: {
              command: ['/bin/bash', '/app/health-check.sh', 'api/validator/readyz'],
            },
            initialDelaySeconds: 5,
            periodSeconds: 15,
            failureThreshold: 5,
            timeoutSeconds: 10,
          },
          resources: {
            requests: {
              cpu: '0.5',
              memory: '2.4Gi',
            },
            limits: {
              cpu: '4',
              memory: '8Gi',
            },
          },
        },
        serviceSpec: { ports },
      },
      opts,
      undefined,
      multiValidatorConfig?.extraValidatorEnvVars
    );
  }
}
