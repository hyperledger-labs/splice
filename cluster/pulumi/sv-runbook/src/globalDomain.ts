import * as k8s from '@pulumi/kubernetes';
import { Resource } from '@pulumi/pulumi';
import {
  ChartValues,
  CnInput,
  ExactNamespace,
  REPO_ROOT,
  installCNRunbookHelmChart,
  loadYamlFromFile,
  DomainMigrationIndex,
} from 'cn-pulumi-common';

import { installCometBftNode } from './cometbft';
import { installPostgres } from './postgres';
import { localCharts, version } from './utils';

export function installGlobalDomainNode(
  svNamespace: ExactNamespace,
  svName: string,
  migrationId: DomainMigrationIndex,
  dependencies: CnInput<Resource>[]
): k8s.helm.v3.Release {
  const cometbft = installCometBftNode(svNamespace, svName, migrationId, dependencies);

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

  const globalDomainValues: ChartValues = {
    ...loadYamlFromFile(
      `${REPO_ROOT}/apps/app/src/pack/examples/sv-helm/global-domain-values.yaml`,
      {
        MIGRATION_ID: migrationId.toString(),
      }
    ),
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
