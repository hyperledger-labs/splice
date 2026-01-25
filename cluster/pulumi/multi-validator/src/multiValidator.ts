// Copyright (c) 2026 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as k8s from '@pulumi/kubernetes';
import * as pulumi from '@pulumi/pulumi';
import {
  DecentralizedSynchronizerMigrationConfig,
  daContactPoint,
  generatePortSequence,
  numNodesPerInstance,
  DecentralizedSynchronizerUpgradeConfig,
} from '@lfdecentralizedtrust/splice-pulumi-common';

import { pvcSuffix, standardStorageClassName } from '../../common/src/storage/storageClass';
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
    // TODO(#2773) consider making this optional so we don't pay for the extra PVCs when we don't anticipate a HDM
    const domainMigrationPvc = new k8s.core.v1.PersistentVolumeClaim(
      `${name}-domain-migration-pvc`,
      {
        metadata: {
          namespace: args.namespace.metadata.name,
          name: `${name}-domain-migration-${pvcSuffix}`,
        },
        spec: {
          accessModes: ['ReadWriteOnce'],
          resources: {
            requests: {
              storage: '20G',
            },
          },
          storageClassName: standardStorageClassName,
        },
      },
      opts
    );

    const migrationEnvVars =
      decentralizedSynchronizerUpgradeConfig.migratingFromActiveId !== undefined
        ? [
            {
              name: 'MULTI_VALIDATOR_ADDITIONAL_CONFIG_INIT_MIGRATION',
              value:
                'canton.validator-apps.validator_backend_INDEX.restore-from-migration-dump = "/domain-upgrade-dump/INDEX/domain_migration_dump.json"',
            },
          ]
        : [
            {
              name: 'MULTI_VALIDATOR_ADDITIONAL_CONFIG_MIGRATION_DUMP_PATH',
              value:
                'canton.validator-apps.validator_backend_INDEX.domain-migration-dump-path = "/domain-upgrade-dump/INDEX/domain_migration_dump.json"',
            },
          ];
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
          ].concat(migrationEnvVars),
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
          resources: multiValidatorConfig?.resources?.validator,
          volumeMounts: [
            {
              name: 'domain-upgrade-dump-volume',
              mountPath: '/domain-upgrade-dump',
            },
          ],
        },
        volumes: [
          {
            name: 'domain-upgrade-dump-volume',
            persistentVolumeClaim: {
              claimName: domainMigrationPvc.metadata.name,
            },
          },
        ],
        serviceSpec: { ports },
      },
      opts,
      undefined,
      multiValidatorConfig?.extraValidatorEnvVars
    );
  }
}
