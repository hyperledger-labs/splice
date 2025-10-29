// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import {
  exactNamespace,
  numInstances,
  imagePullSecret,
  DecentralizedSynchronizerUpgradeConfig,
} from '@lfdecentralizedtrust/splice-pulumi-common';
import { installLoopback } from '@lfdecentralizedtrust/splice-pulumi-common-sv';

import { multiValidatorConfig } from './config';
import { MultiParticipant } from './multiParticipant';
import { MultiValidator } from './multiValidator';
import { installPostgres } from './postgres';

export async function installNode(): Promise<void> {
  const namespace = exactNamespace('multi-validator', true);
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
      `multi-participant-${i}`,
      {
        namespace: namespace.ns,
        postgres: {
          ...postgresConf,
          db: multiValidatorConfig?.useStaticParticipantDatabase
            ? `cantonnet_p`
            : `participant_${DecentralizedSynchronizerUpgradeConfig.active.id}`,
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
