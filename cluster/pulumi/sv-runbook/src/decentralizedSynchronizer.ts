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
} from 'cn-pulumi-common';

import { installCometBftNode } from './cometbft';
import { installPostgres } from './postgres';

export function installDecentralizedSynchronizerNode(
  svNamespace: ExactNamespace,
  svName: string,
  decentralizedSynchronizerMigrationConfig: DecentralizedSynchronizerMigrationConfig,
  dependencies: CnInput<Resource>[]
): k8s.helm.v3.Release {
  const cometbft = installCometBftNode(
    svNamespace,
    svName,
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
        },
        version,
        dependencies.concat([cometbft, sequencerPg, mediatorPg])
      );
    }
  ).activeComponent;
}
