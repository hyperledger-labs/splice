import { Output, Resource } from '@pulumi/pulumi';
import svConfigs from 'canton-network-pulumi-deployment/src/svConfigs';
import {
  Auth0Client,
  CnInput,
  DecentralizedSynchronizerMigrationConfig,
  ExactNamespace,
  MigrationProvider,
} from 'splice-pulumi-common';
import {
  CometBftNodeConfigs,
  cometbftRetainBlocks,
  CrossStackDecentralizedSynchronizerNode,
  installCantonComponents,
  InstalledMigrationSpecificSv,
} from 'splice-pulumi-common-sv';

import { installCometbftKeys } from './cometbftKeys';
import { installPostgres } from './postgres';

export function installCanton(
  svNamespace: ExactNamespace,
  auth0Client: Auth0Client,
  onboardingName: string,
  decentralizedSynchronizerMigrationConfig: DecentralizedSynchronizerMigrationConfig,
  dependencies: CnInput<Resource>[]
): InstalledMigrationSpecificSv {
  const migrationsContainedInStack = decentralizedSynchronizerMigrationConfig
    .allMigrationInfos()
    .filter(migrationInfo => migrationInfo.provider === MigrationProvider.INTERNAL);
  const activeMigrationId = decentralizedSynchronizerMigrationConfig.active.migrationId;
  const participantPg = installPostgres(
    svNamespace,
    `participant-pg`,
    'participant-pg-secret',
    'postgres-values-participant.yaml'
  );

  const sequencerPg = installPostgres(
    svNamespace,
    `sequencer-pg`,
    'sequencer-pg-secret',
    'postgres-values-sequencer.yaml'
  );
  const mediatorPg = installPostgres(
    svNamespace,
    `mediator-pg`,
    'mediator-pg-secret',
    'postgres-values-mediator.yaml'
  );

  installCometbftKeys(svNamespace);
  const sv1Conf = svConfigs.find(config => config.nodeName === 'sv-1')!;
  const nodeConfigs = {
    self: {
      nodeIndex: 0,
      nodeName: onboardingName,
      retainBlocks: cometbftRetainBlocks,
      id: '9116f5faed79dcf98fa79a2a40865ad9b493f463',
    },
    sv1: {
      ...sv1Conf?.cometBft,
      nodeName: sv1Conf.nodeName,
    },
    peers: [],
  };
  const installedMigrations = migrationsContainedInStack.map(migration => {
    return {
      migration,
      canton: installCantonComponents(
        svNamespace,
        migration.migrationId,
        auth0Client,
        {
          onboardingName,
          isFirstSv: false,
          isCoreSv: false,
        },
        decentralizedSynchronizerMigrationConfig,
        {
          nodeConfigs: nodeConfigs,
        },
        {
          participant: participantPg,
          mediator: mediatorPg,
          sequencer: sequencerPg,
        },
        {
          dependsOn: dependencies,
        }
      ),
    };
  });
  return (
    installedMigrations.find(({ migration }) => {
      return migration.migrationId === activeMigrationId;
    })?.canton || {
      decentralizedSynchronizer: new CrossStackDecentralizedSynchronizerNode(
        activeMigrationId,
        new CometBftNodeConfigs(activeMigrationId, nodeConfigs).nodeIdentifier
      ),
      participant: {
        asDependencies: [],
        internalClusterAddress: Output.create(`participant-${activeMigrationId}`),
      },
    }
  );
}
