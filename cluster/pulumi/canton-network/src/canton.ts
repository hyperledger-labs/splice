import * as postgres from 'splice-pulumi-common/src/postgres';
import { Output, Resource } from '@pulumi/pulumi';
import {
  CnInput,
  DecentralizedSynchronizerMigrationConfig,
  ExactNamespace,
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
  },
  svConfig: SvConfig,
  dependsOn: CnInput<Resource>[]
): InstalledMigrationSpecificSv {
  const migrationsContainedInStack = decentralizedSynchronizerMigrationConfig.allInternalMigrations;
  const activeMigrationId =
    decentralizedSynchronizerMigrationConfig.activeDatabaseId ||
    decentralizedSynchronizerMigrationConfig.active.id;
  const externalActiveMigration = {
    decentralizedSynchronizer: new CrossStackDecentralizedSynchronizerNode(
      activeMigrationId,
      new CometBftNodeConfigs(activeMigrationId, cometbft.nodeConfigs).nodeIdentifier
    ),
    participant: {
      asDependencies: [],
      internalClusterAddress: Output.create(`participant-${activeMigrationId}`),
    },
  };
  if (migrationsContainedInStack.length > 0) {
    const sequencerPostgres =
      defaultPostgres ||
      postgres.installPostgres(
        xns,
        `sequencer-pg`,
        `sequencer-${activeMigrationId}-pg`,
        true,
        decentralizedSynchronizerMigrationConfig.hasInternalRunningMigration,
        activeMigrationId
      );
    const mediatorPostgres =
      defaultPostgres ||
      postgres.installPostgres(
        xns,
        `mediator-pg`,
        `mediator-${activeMigrationId}-pg`,
        true,
        decentralizedSynchronizerMigrationConfig.hasInternalRunningMigration,
        activeMigrationId
      );
    const participantPostgres =
      defaultPostgres ||
      postgres.installPostgres(
        xns,
        `participant-pg`,
        `participant-${activeMigrationId}-pg`,
        true,
        decentralizedSynchronizerMigrationConfig.hasInternalRunningMigration,
        activeMigrationId
      );
    const installedMigrations = migrationsContainedInStack.map(migration => {
      return {
        migration,
        canton: installCantonComponents(
          xns,
          migration.id,
          svConfig.auth0Client,
          {
            onboardingName: svConfig.onboardingName,
            isFirstSv: svConfig.isFirstSv,
            isCoreSv: true,
          },
          decentralizedSynchronizerMigrationConfig,
          {
            ...cometbft,
            // State sync doesn't make sense in the main stack, as all the cometbft nodes are started at the same time
            enableStateSync: false,
            enableTimeoutCommit:
              svConfig.isFirstSv &&
              decentralizedSynchronizerMigrationConfig.runningMigrations().length > 1,
          },
          {
            participant: participantPostgres,
            mediator: mediatorPostgres,
            sequencer: sequencerPostgres,
          },
          { dependsOn: dependsOn }
        ),
      };
    });
    return (
      installedMigrations.find(
        installedMigration => installedMigration.migration.id === activeMigrationId
      )?.canton || externalActiveMigration
    );
  } else {
    return externalActiveMigration;
  }
}
