import {
  exactNamespace,
  CLUSTER_HOSTNAME,
  installLoopback,
  numInstances,
  defaultVersion,
} from 'splice-pulumi-common';

import { MultiParticipant } from './multiParticipant';
import { MultiValidator } from './multiValidator';
import { installPostgres } from './postgres';

export async function installNode(): Promise<void> {
  const namespace = exactNamespace('multi-validator', true);
  installLoopback(namespace, CLUSTER_HOSTNAME, defaultVersion);

  for (let i = 0; i < numInstances; i++) {
    const postgres = installPostgres(namespace, `postgres-${i}`);
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
        postgres: { ...postgresConf, db: `cantonnet_p` },
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
      { dependsOn: [postgres] }
    );
  }
}
