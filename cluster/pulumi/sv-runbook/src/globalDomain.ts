import * as k8s from '@pulumi/kubernetes';
import { Output, Resource } from '@pulumi/pulumi';
import {
  ChartValues,
  CnInput,
  ExactNamespace,
  REPO_ROOT,
  installCNRunbookHelmChart,
  loadYamlFromFile,
} from 'cn-pulumi-common';

import { installCometBftNode } from './cometbft';
import { localCharts, version } from './utils';

export function installGlobalDomainNode(
  svNamespace: ExactNamespace,
  postgresPassword: Output<string>,
  svName: string,
  dependencies: CnInput<Resource>[]
): k8s.helm.v3.Release {
  const cometbft = installCometBftNode(svNamespace, svName, dependencies);

  const globalDomainValues: ChartValues = {
    ...loadYamlFromFile(
      `${REPO_ROOT}/apps/app/src/pack/examples/sv-helm/global-domain-values.yaml`,
      {}
    ),
    postgresPassword: postgresPassword,
  };
  return installCNRunbookHelmChart(
    svNamespace,
    'global-domain',
    'cn-global-domain',
    globalDomainValues,
    localCharts,
    version,
    dependencies.concat([cometbft])
  );
}
