import * as k8s from '@pulumi/kubernetes';
import { Resource } from '@pulumi/pulumi';
import {
  ChartValues,
  CnInput,
  ExactNamespace,
  REPO_ROOT,
  installCNRunbookHelmChart,
  loadYamlFromFile,
} from 'cn-pulumi-common';

import { installCometBftNode } from './cometbft';
import { installPostgres } from './postgres';
import { localCharts, version } from './utils';

export function installGlobalDomainNode(
  svNamespace: ExactNamespace,
  svName: string,
  dependencies: CnInput<Resource>[]
): k8s.helm.v3.Release {
  const cometbft = installCometBftNode(svNamespace, svName, dependencies);

  const sequencerPgValues = loadYamlFromFile(
    `${REPO_ROOT}/apps/app/src/pack/examples/sv-helm/postgres-values-sequencer.yaml`
  );
  const sequencerPg = installPostgres(svNamespace, 'sequencer-pg', sequencerPgValues);
  const mediatorPgValues = loadYamlFromFile(
    `${REPO_ROOT}/apps/app/src/pack/examples/sv-helm/postgres-values-mediator.yaml`
  );
  const mediatorPg = installPostgres(svNamespace, 'mediator-pg', mediatorPgValues);

  const globalDomainValues: ChartValues = {
    ...loadYamlFromFile(
      `${REPO_ROOT}/apps/app/src/pack/examples/sv-helm/global-domain-values.yaml`,
      {}
    ),
  };
  return installCNRunbookHelmChart(
    svNamespace,
    'global-domain',
    'cn-global-domain',
    globalDomainValues,
    localCharts,
    version,
    dependencies.concat([cometbft, sequencerPg, mediatorPg])
  );
}
