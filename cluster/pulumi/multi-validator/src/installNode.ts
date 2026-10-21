// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import {
  exactNamespace,
  imagePullSecret,
  DecentralizedSynchronizerUpgradeConfig,
} from '@canton-network/splice-pulumi-common';
import { installLoopback } from '@canton-network/splice-pulumi-common-sv';
import { MultiValidatorConfig } from '@canton-network/splice-pulumi-common/src/config/multiValidatorConfig';

import { MultiParticipant } from './multiParticipant';
import { MultiValidator } from './multiValidator';
import { installPostgres } from './postgres';

export async function installNode(config: MultiValidatorConfig): Promise<void> {
  const namespace = exactNamespace('multi-validator', true);
  installLoopback(namespace);

  const imagePullDeps = imagePullSecret(namespace);

  for (let i = 0; i < config.instanceCount; i++) {
    const postgres = installPostgres(namespace, `postgres-${i}`, config, imagePullDeps);
    const postgresConf = {
      // being just the init container it doesn't matter too much so as long as it has psql
      initImageName: 'postgres:18',
      host: postgres.address,
      port: '5432',
      schema: 'cantonnet',
      secret: {
        name: postgres.secretName,
        key: 'postgresPassword',
      },
    };

    const participant = new MultiParticipant(
      `multi-participant-${i}-${DecentralizedSynchronizerUpgradeConfig.activeMigrationId}`,
      {
        namespace: namespace.ns,
        postgres: {
          ...postgresConf,
          db: `participant_${DecentralizedSynchronizerUpgradeConfig.activeMigrationId}`,
        },
        config,
      },
      { dependsOn: [postgres] }
    );

    new MultiValidator(
      `multi-validator-${i}`,
      {
        namespace: namespace.ns,
        participant: { address: participant.service.metadata.name },
        postgres: { ...postgresConf, db: `cantonnet_v` },
        config,
      },
      { dependsOn: [postgres, participant] }
    );
  }
}
