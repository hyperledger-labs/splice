import { Output, Resource } from '@pulumi/pulumi';
import {
  Auth0Client,
  CnInput,
  DecentralizedSynchronizerMigrationConfig,
  ExactNamespace,
} from 'splice-pulumi-common';
import {
  CometBftNodeConfigs,
  CrossStackCometBftDecentralizedSynchronizerNode,
  CrossStackDecentralizedSynchronizerNode,
  installCantonComponents,
  InstalledMigrationSpecificSv,
  sv1Config,
  svRunbookConfig,
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
  const migrationsContainedInStack = decentralizedSynchronizerMigrationConfig.allInternalMigrations;
  const activeMigrationId = decentralizedSynchronizerMigrationConfig.active.id;
  const nodeConfigs = {
    self: {
      ...svRunbookConfig.cometBft,
      nodeName: onboardingName,
    },
    sv1: {
      ...sv1Config?.cometBft,
      nodeName: sv1Config.nodeName,
    },
    peers: [],
  };
  installCometbftKeys(svNamespace);
  const externalActiveMigration = {
    decentralizedSynchronizer: decentralizedSynchronizerMigrationConfig.active.sequencer
      .enableBftSequencer
      ? new CrossStackDecentralizedSynchronizerNode(activeMigrationId)
      : new CrossStackCometBftDecentralizedSynchronizerNode(
          activeMigrationId,
          new CometBftNodeConfigs(activeMigrationId, nodeConfigs).nodeIdentifier
        ),
    participant: {
      asDependencies: [],
      internalClusterAddress: Output.create(`participant-${activeMigrationId}`),
    },
  };
  if (migrationsContainedInStack.length > 0) {
    const participantPg = installPostgres(
      svNamespace,
      `participant-pg`,
      'participant-pg-secret',
      'postgres-values-participant.yaml',
      decentralizedSynchronizerMigrationConfig.hasInternalRunningMigration
    );

    const sequencerPg = installPostgres(
      svNamespace,
      `sequencer-pg`,
      'sequencer-pg-secret',
      'postgres-values-sequencer.yaml',
      decentralizedSynchronizerMigrationConfig.hasInternalRunningMigration
    );
    const mediatorPg = installPostgres(
      svNamespace,
      `mediator-pg`,
      'mediator-pg-secret',
      'postgres-values-mediator.yaml',
      decentralizedSynchronizerMigrationConfig.hasInternalRunningMigration
    );

    const installedMigrations = migrationsContainedInStack.map(migration => {
      return {
        migration,
        canton: installCantonComponents(
          svNamespace,
          migration.id,
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
        return migration.id === activeMigrationId;
      })?.canton || externalActiveMigration
    );
  } else {
    return externalActiveMigration;
  }
}
