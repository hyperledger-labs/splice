import * as postgres from 'splice-pulumi-common/src/postgres';
import { Release } from '@pulumi/kubernetes/helm/v3';
import { Output } from '@pulumi/pulumi';
import {
  DecentralizedSynchronizerMigrationConfig,
  ExactNamespace,
  MigrationProvider,
} from 'splice-pulumi-common';
import {
  CometBftNodeConfigs,
  CrossStackDecentralizedSynchronizerNode,
  installCantonComponents,
  InstalledMigrationSpecificSv,
  StaticCometBftConfigWithNodeName,
} from 'splice-pulumi-common-sv';
import { SvConfig } from 'splice-pulumi-common-sv/src/config';
import { Postgres } from 'splice-pulumi-common/src/postgres';

export function installCanton(
  xns: ExactNamespace,
  decentralizedSynchronizerMigrationConfig: DecentralizedSynchronizerMigrationConfig,
  defaultPostgres: Postgres | undefined,
  cometbft: {
    name: string;
    onboardingName: string;
    nodeConfigs: {
      self: StaticCometBftConfigWithNodeName;
      sv1: StaticCometBftConfigWithNodeName;
      peers: StaticCometBftConfigWithNodeName[];
    };
    sv1SvApp?: Release;
  },
  svConfig: SvConfig
): InstalledMigrationSpecificSv {
  const migrationsContainedInStack = decentralizedSynchronizerMigrationConfig
    .allMigrationInfos()
    .filter(migrationInfo => migrationInfo.provider === MigrationProvider.INTERNAL);
  const databaseSuffix = decentralizedSynchronizerMigrationConfig.activeDatabaseId
    ? `${decentralizedSynchronizerMigrationConfig.activeDatabaseId}-pg`
    : 'pg';
  const activeMigrationId = decentralizedSynchronizerMigrationConfig.active.migrationId;
  const sequencerPostgres =
    defaultPostgres ||
    postgres.installPostgres(
      xns,
      `sequencer-${databaseSuffix}`,
      `sequencer-${activeMigrationId}-pg`,
      true
    );
  const mediatorPostgres =
    defaultPostgres ||
    postgres.installPostgres(
      xns,
      `mediator-${databaseSuffix}`,
      `mediator-${activeMigrationId}-pg`,
      true
    );
  const participantPostgres =
    defaultPostgres ||
    postgres.installPostgres(
      xns,
      `participant-${databaseSuffix}`,
      `participant-${activeMigrationId}-pg`,
      true
    );
  const installedMigrations = migrationsContainedInStack.map(migration => {
    return {
      migration,
      canton: installCantonComponents(
        xns,
        migration.migrationId,
        svConfig.auth0Client,
        {
          onboardingName: svConfig.onboardingName,
          isFirstSv: svConfig.isFirstSv,
          isCoreSv: true,
        },
        decentralizedSynchronizerMigrationConfig,
        cometbft,
        {
          participant: participantPostgres,
          mediator: mediatorPostgres,
          sequencer: sequencerPostgres,
        }
      ),
    };
  });
  return (
    installedMigrations.find(
      installedMigration => installedMigration.migration.migrationId === activeMigrationId
    )?.canton || {
      decentralizedSynchronizer: new CrossStackDecentralizedSynchronizerNode(
        activeMigrationId,
        new CometBftNodeConfigs(activeMigrationId, cometbft.nodeConfigs).nodeIdentifier
      ),
      participant: {
        asDependencies: [],
        internalClusterAddress: Output.create(`participant-${activeMigrationId}`),
      },
    }
  );
}
