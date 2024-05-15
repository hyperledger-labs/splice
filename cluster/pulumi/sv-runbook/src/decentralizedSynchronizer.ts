import * as k8s from '@pulumi/kubernetes';
import { Resource } from '@pulumi/pulumi';
import {
  ChartValues,
  CnInput,
  ExactNamespace,
  DecentralizedSynchronizerMigrationConfig,
  REPO_ROOT,
  installCNRunbookHelmChart,
  installMigrationIdSpecificComponent,
  loadYamlFromFile,
  sequencerResources,
  sequencerTokenExpirationTime,
  config,
} from 'cn-pulumi-common';

import { installCometBftNode } from './cometbft';
import { installPostgres } from './postgres';

export function installDecentralizedSynchronizerNode(
  svNamespace: ExactNamespace,
  svName: string,
  decentralizedSynchronizerMigrationConfig: DecentralizedSynchronizerMigrationConfig,
  dependencies: CnInput<Resource>[]
): k8s.helm.v3.Release {
  const logLevel = config.envFlag('CN_DEPLOYMENT_SINGLE_SV_DEBUG') ? 'INFO' : 'DEBUG';
  const cometbft = installCometBftNode(
    svNamespace,
    svName,
    logLevel.toLowerCase(),
    decentralizedSynchronizerMigrationConfig,
    dependencies
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

  return installMigrationIdSpecificComponent(
    decentralizedSynchronizerMigrationConfig,
    (migrationId, isActive, version) => {
      const decentralizedSynchronizerValues: ChartValues = {
        ...loadYamlFromFile(
          `${REPO_ROOT}/apps/app/src/pack/examples/sv-helm/global-domain-values.yaml`,
          {
            MIGRATION_ID: migrationId.toString(),
            YOUR_SV_NAME: svName,
          }
        ),
        metrics: {
          enable: true,
          migration: {
            id: migrationId,
            active: isActive,
          },
        },
        disableAutoInit: decentralizedSynchronizerMigrationConfig.isRunningMigration() || !isActive,
      };

      return installCNRunbookHelmChart(
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
              postgresName: sequencerPg.name,
            },
          },
          mediator: {
            ...decentralizedSynchronizerValues.mediator,
            persistence: {
              ...decentralizedSynchronizerValues.mediator.persistence,
              host: mediatorPg.address,
              postgresName: mediatorPg.name,
            },
          },
          enablePostgresMetrics: true,
          logLevel: logLevel,
        },
        version,
        dependencies.concat([cometbft, sequencerPg, mediatorPg])
      );
    }
  ).activeComponent;
}
