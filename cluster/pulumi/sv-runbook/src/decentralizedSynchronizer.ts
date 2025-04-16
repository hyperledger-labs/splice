import { Output, Resource } from '@pulumi/pulumi';
import {
  Auth0Client,
  CnInput,
  DecentralizedSynchronizerMigrationConfig,
  ExactNamespace,
} from 'splice-pulumi-common';
import {
  CometBftNodeConfigs,
  CrossStackDecentralizedSynchronizerNode,
  installCantonComponents,
  InstalledMigrationSpecificSv,
  sv1Config,
  svRunbookConfig,
} from 'splice-pulumi-common-sv';

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
  const externalActiveMigration = {
    decentralizedSynchronizer: new CrossStackDecentralizedSynchronizerNode(
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

    // TODO(#16751) "internal" migrations are likely broken at this point; let's remove them
    const installedMigrations = migrationsContainedInStack.map(migration => {
      return {
        migration,
        canton: installCantonComponents(
          svNamespace,
          migration.id,
          auth0Client,
          {
            onboardingName,
            // TODO(#16751) The hardcoding is not nice but we're getting rid of this code path anyway
            auth0SvAppName: 'sv',
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
