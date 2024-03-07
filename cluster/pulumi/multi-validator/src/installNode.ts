import { exactNamespace, CLUSTER_BASENAME, installLoopback } from 'cn-pulumi-common';

import { MultiParticipant } from './multiParticipant';
import { MultiValidator } from './multiValidator';
import { installPostgres } from './postgres';

export async function installNode(): Promise<void> {
  const namespace = exactNamespace('multi-validator', true);
  const postgres = installPostgres(namespace, 'postgres');

  const secretRef = { name: 'postgres-secret', key: 'postgresPassword' };

  installLoopback(namespace, CLUSTER_BASENAME, true, undefined);

  const numNodes = 10;
  const usernamePrefix = 'validator_user';
  const postgresConf = {
    host: 'postgres',
    port: '5432',
    schema: 'cantonnet',
  };

  const multiparticipant = new MultiParticipant(
    'multi-participant',
    {
      namespace: namespace.ns,
      usernamePrefix,
      numNodes,
      postgres: { ...postgresConf, db: 'cantonnet_p' },
      secretRef,
    },
    { dependsOn: [postgres] }
  );

  new MultiValidator(
    'multi-validator',
    {
      namespace: namespace.ns,
      usernamePrefix,
      numNodes,
      postgres: { ...postgresConf, db: 'cantonnet_v' },
      secretRef,
    },
    { dependsOn: [multiparticipant] }
  );
}
