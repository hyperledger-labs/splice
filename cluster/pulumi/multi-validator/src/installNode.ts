// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as k8s from '@pulumi/kubernetes';
import {
  exactNamespace,
  numInstances,
  imagePullSecret,
  DecentralizedSynchronizerUpgradeConfig,
} from '@lfdecentralizedtrust/splice-pulumi-common';
import { installLoopback } from '@lfdecentralizedtrust/splice-pulumi-common-sv';

import { MultiParticipant } from './multiParticipant';
import { MultiValidator } from './multiValidator';
import { installPostgres } from './postgres';

export async function installNode(): Promise<void> {
  const namespace = exactNamespace('multi-validator', true);
  const podManagementRole = new k8s.rbac.v1.Role(
    'gc-pod-reaper-pod-manager-role',
    {
      metadata: {
        name: 'gc-pod-reaper-pod-manager-role',
        namespace: namespace.ns.metadata.name,
      },
      rules: [
        {
          apiGroups: [''], // Core API group for Pods
          resources: ['pods'],
          verbs: ['list', 'create', 'delete', 'update'],
        },
      ],
    },
    { parent: namespace.ns }
  );

  new k8s.rbac.v1.RoleBinding(
    'gc-pod-reaper-pod-manager-binding',
    {
      metadata: {
        name: 'gc-pod-reaper-pod-manager-binding',
        namespace: namespace.ns.metadata.name,
      },
      subjects: [
        {
          kind: 'ServiceAccount',
          name: 'gc-pod-reaper-service-account',
          namespace: 'gc-pod-reaper',
        },
      ],
      roleRef: {
        kind: 'Role',
        name: podManagementRole.metadata.name,
        apiGroup: 'rbac.authorization.k8s.io',
      },
    },
    { parent: namespace.ns, dependsOn: [podManagementRole] }
  );
  installLoopback(namespace);

  const imagePullDeps = imagePullSecret(namespace);

  for (let i = 0; i < numInstances; i++) {
    const postgres = installPostgres(namespace, `postgres-${i}`, imagePullDeps);
    const postgresConf = {
      host: `postgres-${i}`,
      port: '5432',
      schema: 'cantonnet',
      secret: {
        name: `postgres-${i}-secret`,
        key: 'postgresPassword',
      },
    };

    const participant = new MultiParticipant(
      `multi-participant-${i}-${DecentralizedSynchronizerUpgradeConfig.active.id}`,
      {
        namespace: namespace.ns,
        postgres: {
          ...postgresConf,
          db: `participant_${DecentralizedSynchronizerUpgradeConfig.active.id}`,
        },
      },
      { dependsOn: [postgres] }
    );

    new MultiValidator(
      `multi-validator-${i}`,
      {
        namespace: namespace.ns,
        participant: { address: participant.service.metadata.name },
        postgres: { ...postgresConf, db: `cantonnet_v` },
      },
      { dependsOn: [postgres, participant] }
    );
  }
}
