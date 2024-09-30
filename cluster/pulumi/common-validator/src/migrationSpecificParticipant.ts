import * as pulumi from '@pulumi/pulumi';
import * as postgres from 'splice-pulumi-common/src/postgres';
import { Output } from '@pulumi/pulumi';
import {
  Auth0Config,
  auth0UserNameEnvVarSource,
  CnInput,
  DecentralizedSynchronizerMigrationConfig,
  ExactNamespace,
  installMigrationIdSpecificComponent,
  LogLevel,
} from 'splice-pulumi-common';

import { installParticipant } from './participant';

export function installMigrationSpecificValidatorParticipant(
  decentralizedSynchronizerMigrationConfig: DecentralizedSynchronizerMigrationConfig,
  xns: ExactNamespace,
  defaultPostgres: postgres.Postgres | undefined,
  nodeIdentifier: string,
  auth0Cfg: Auth0Config,
  logLevel?: LogLevel,
  dependsOn: CnInput<pulumi.Resource>[] = []
): {
  participantAddress: Output<string>;
} {
  const participantPostgres =
    defaultPostgres ||
    postgres.installPostgres(
      xns,
      'participant-pg',
      `participant-${decentralizedSynchronizerMigrationConfig.active.id}-pg`,
      true
    );
  return installMigrationIdSpecificComponent(
    decentralizedSynchronizerMigrationConfig,
    (migrationId, _, version) => {
      return installParticipant(
        decentralizedSynchronizerMigrationConfig,
        migrationId,
        xns,
        auth0Cfg,
        nodeIdentifier,
        auth0UserNameEnvVarSource('validator'),
        version,
        participantPostgres,
        logLevel,
        {
          dependsOn,
        }
      );
    }
  ).activeComponent;
}
