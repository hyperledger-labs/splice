import * as pulumi from '@pulumi/pulumi';
import { Output } from '@pulumi/pulumi';
import {
  Auth0Config,
  auth0UserNameEnvVarSource,
  DecentralizedSynchronizerMigrationConfig,
  ExactNamespace,
  installMigrationIdSpecificComponent,
  LogLevel,
} from 'splice-pulumi-common';
import { installParticipant } from 'splice-pulumi-common-validator';

import * as postgres from '../../common/src/postgres';

export function installMigrationSpecificValidatorParticipant(
  decentralizedSynchronizerMigrationConfig: DecentralizedSynchronizerMigrationConfig,
  xns: ExactNamespace,
  defaultPostgres: postgres.Postgres | undefined,
  nodeIdentifier: string,
  auth0Cfg: Auth0Config,
  logLevel?: LogLevel,
  dependsOn: pulumi.Resource[] = []
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
