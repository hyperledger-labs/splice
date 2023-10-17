import * as k8s from '@pulumi/kubernetes';
import { Input, Output, Resource } from '@pulumi/pulumi';
import {
  ChartValues,
  ExactNamespace,
  REPO_ROOT,
  installCNSVHelmChart,
  loadYamlFromFile,
} from 'cn-pulumi-common';
import { domainFeesConfig } from 'cn-pulumi-common/src/domainFeesCfg';
import { globalDomainSequencerDriver } from 'cn-pulumi-common/src/global-domain';

import { installCometBftNode } from './cometbft';
import { localCharts, version, withDomainFees } from './utils';

export const includesCometBftGlobalDomainNode = globalDomainSequencerDriver == 'cometbft';

export function installGlobalDomainNode(
  svNamespace: ExactNamespace,
  postgresPassword: Output<string>,
  svName: string,
  dependencies: Input<Resource>[]
): k8s.helm.v3.Release {
  const cometbft = installCometBftNode(svNamespace, svName, dependencies);

  if (includesCometBftGlobalDomainNode) {
    const globalDomainValues: ChartValues = {
      ...loadYamlFromFile(
        `${REPO_ROOT}/apps/app/src/pack/examples/sv-helm/global-domain-values.yaml`,
        {}
      ),
      postgresPassword: postgresPassword,
      trafficControl: withDomainFees
        ? {
            enabled: true,
            baseRate: domainFeesConfig.baseRate,
            maxBurstDuration: domainFeesConfig.maxBurstDuration,
          }
        : {},
    };
    return installCNSVHelmChart(
      svNamespace,
      'global-domain',
      'cn-global-domain',
      globalDomainValues,
      localCharts,
      version,
      dependencies.concat([cometbft])
    );
  } else {
    return cometbft;
  }
}
