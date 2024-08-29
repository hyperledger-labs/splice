import * as k8s from '@pulumi/kubernetes';
import { Resource } from '@pulumi/pulumi';
import svConfigs from 'canton-network-pulumi-deployment/src/svConfigs';
import {
  ChartValues,
  CnInput,
  ExactNamespace,
  DecentralizedSynchronizerMigrationConfig,
  REPO_ROOT,
  installSpliceRunbookHelmChart,
  installMigrationIdSpecificComponent,
  loadYamlFromFile,
  sequencerResources,
  sequencerTokenExpirationTime,
  config,
  autoInitValues,
} from 'cn-pulumi-common';
import { CometBftNodeConfigs } from 'cn-pulumi-common/src/synchronizer/cometBftNodeConfigs';
import { installCometBftNode } from 'cn-pulumi-common/src/synchronizer/cometbft';
import { cometbftRetainBlocks } from 'cn-pulumi-common/src/synchronizer/cometbftConfig';

import { installCometbftKeys } from './cometbftKeys';
import { installPostgres } from './postgres';

export function installDecentralizedSynchronizerNode(
  svNamespace: ExactNamespace,
  onboardingName: string,
  decentralizedSynchronizerMigrationConfig: DecentralizedSynchronizerMigrationConfig,
  dependencies: CnInput<Resource>[]
): k8s.helm.v3.Release {
  const logLevel =
    config.envFlag('SPLICE_DEPLOYMENT_SINGLE_SV_DEBUG') ||
    config.envFlag('SPLICE_DEPLOYMENT_NO_SV_DEBUG')
      ? 'INFO'
      : 'DEBUG';

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
    (migrationId, isActive, version) => {
      const sv1Conf = svConfigs.find(config => config.nodeName === 'sv-1')!;
      const cometbft = installCometBftNode(
        svNamespace,
        onboardingName,
        new CometBftNodeConfigs(migrationId, {
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
        }),
        migrationId,
        isActive,
        decentralizedSynchronizerMigrationConfig.isRunningMigration(),
        logLevel.toLowerCase(),
        version,
        undefined,
        {
          dependsOn: dependencies,
        }
      );
      const decentralizedSynchronizerValues: ChartValues = {
        ...loadYamlFromFile(
          `${REPO_ROOT}/apps/app/src/pack/examples/sv-helm/global-domain-values.yaml`,
          {
            MIGRATION_ID: migrationId.toString(),
          }
        ),
        metrics: {
          enable: true,
          migration: {
            id: migrationId,
            active: isActive,
          },
        },
      };

      return installSpliceRunbookHelmChart(
        svNamespace,
        `global-domain-${migrationId}`,
        'cn-global-domain',
        {
          ...decentralizedSynchronizerValues,
          sequencer: {
            ...decentralizedSynchronizerValues.sequencer,
            ...sequencerResources,
            tokenExpirationTime: sequencerTokenExpirationTime,
            persistence: {
              ...decentralizedSynchronizerValues.sequencer.persistence,
              host: sequencerPg.address,
              postgresName: sequencerPg.instanceName,
            },
          },
          mediator: {
            ...decentralizedSynchronizerValues.mediator,
            persistence: {
              ...decentralizedSynchronizerValues.mediator.persistence,
              host: mediatorPg.address,
              postgresName: mediatorPg.instanceName,
            },
          },
          enablePostgresMetrics: true,
          logLevel: logLevel,
          ...autoInitValues('cn-global-domain', version, onboardingName),
        },
        version,
        {
          dependsOn: dependencies.concat([cometbft.release, sequencerPg, mediatorPg]),
        }
      );
    }
  ).activeComponent;
}
