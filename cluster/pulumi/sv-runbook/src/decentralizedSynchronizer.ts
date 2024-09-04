import { Resource } from '@pulumi/pulumi';
import svConfigs from 'canton-network-pulumi-deployment/src/svConfigs';
import {
  Auth0Client,
  CnInput,
  DecentralizedSynchronizerMigrationConfig,
  ExactNamespace,
  installMigrationIdSpecificComponent,
} from 'splice-pulumi-common';
import {
  cometbftRetainBlocks,
  DecentralizedSynchronizerNode,
  installCantonComponents,
} from 'splice-pulumi-common-sv';

import { installCometbftKeys } from './cometbftKeys';
import { installPostgres } from './postgres';

export function installCanton(
  svNamespace: ExactNamespace,
  auth0Client: Auth0Client,
  onboardingName: string,
  decentralizedSynchronizerMigrationConfig: DecentralizedSynchronizerMigrationConfig,
  dependencies: CnInput<Resource>[]
): { decentralizedSynchronizer: DecentralizedSynchronizerNode; participant: Resource } {
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

  return installMigrationIdSpecificComponent(
    decentralizedSynchronizerMigrationConfig,
    (migrationId, _, version) => {
      const sv1Conf = svConfigs.find(config => config.nodeName === 'sv-1')!;
      return installCantonComponents(
        svNamespace,
        migrationId,
        auth0Client,
        {
          onboardingName,
          isFirstSv: false,
          isCoreSv: false,
        },
        {
          participant: participantPg,
          mediator: mediatorPg,
          sequencer: sequencerPg,
        },
        version,
        decentralizedSynchronizerMigrationConfig,
        {
          nodeConfigs: {
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
          },
        },
        {
          dependsOn: dependencies,
        }
      );
    }
  ).activeComponent;
}
