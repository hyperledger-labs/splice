import * as k8s from '@pulumi/kubernetes';
import { Resource } from '@pulumi/pulumi';
import {
  ChartValues,
  CnInput,
  ExactNamespace,
  GlobalDomainMigrationConfig,
  REPO_ROOT,
  installCNRunbookHelmChart,
  installMigrationIdSpecificComponent,
  loadYamlFromFile,
} from 'cn-pulumi-common';

import { installCometBftNode } from './cometbft';
import { installPostgres } from './postgres';
import { localCharts, version } from './utils';

export function installGlobalDomainNode(
  svNamespace: ExactNamespace,
  svName: string,
  globalDomainMigrationConfig: GlobalDomainMigrationConfig,
  dependencies: CnInput<Resource>[]
): k8s.helm.v3.Release {
  const cometbft = installCometBftNode(
    svNamespace,
    svName,
    globalDomainMigrationConfig,
    dependencies
  );

  const sequencerPgValues = loadYamlFromFile(
    `${REPO_ROOT}/apps/app/src/pack/examples/sv-helm/postgres-values-sequencer.yaml`
  );
  const sequencerPg = installPostgres(
    svNamespace,
    `sequencer-pg`,
    'sequencer-pg-secret',
    sequencerPgValues
  );
  const mediatorPgValues = loadYamlFromFile(
    `${REPO_ROOT}/apps/app/src/pack/examples/sv-helm/postgres-values-mediator.yaml`
  );
  const mediatorPg = installPostgres(
    svNamespace,
    `mediator-pg`,
    'mediator-pg-secret',
    mediatorPgValues
  );

  return installMigrationIdSpecificComponent(
    globalDomainMigrationConfig,
    (migrationId, isActive) => {
      const globalDomainValues: ChartValues = {
        ...loadYamlFromFile(
          `${REPO_ROOT}/apps/app/src/pack/examples/sv-helm/global-domain-values.yaml`,
          {
            MIGRATION_ID: migrationId.toString(),
          }
        ),
        disableAutoInit: globalDomainMigrationConfig.isRunningMigration() || !isActive,
      };
      return installCNRunbookHelmChart(
        svNamespace,
        `global-domain-${migrationId}`,
        'cn-global-domain',
        globalDomainValues,
        localCharts,
        version,
        dependencies.concat([cometbft, sequencerPg, mediatorPg])
      );
    }
  ).activeComponent;
}
