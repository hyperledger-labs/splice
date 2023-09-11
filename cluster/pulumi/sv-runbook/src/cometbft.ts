import * as k8s from '@pulumi/kubernetes';
import * as fs from 'fs';
import * as _ from 'lodash';
import { Resource } from '@pulumi/pulumi';
import { ExactNamespace, isDevNet, loadYamlFromFile, REPO_ROOT } from 'cn-pulumi-common';

import { installCNSVHelmChart } from './helm';
import { CLUSTER_BASENAME, localCharts, SV_NAME, TARGET_CLUSTER, version } from './utils';

const cometBftValues = loadYamlFromFile(
  `${REPO_ROOT}/apps/app/src/pack/examples/sv-helm/cometbft-values.yaml`,
  {
    TARGET_CLUSTER: TARGET_CLUSTER,
    YOUR_SV_NAME: SV_NAME,
    YOUR_COMETBFT_NODE_ID: '9116f5faed79dcf98fa79a2a40865ad9b493f463',
    YOUR_HOSTNAME: `${CLUSTER_BASENAME}.network.canton.global`,
  }
);

const nodeKeyContent = fs.readFileSync(
  `${REPO_ROOT}/cluster/pulumi/sv-runbook/cometbft/node_key.json`,
  'utf-8'
);
const privValidatorKeyContent = fs.readFileSync(
  `${REPO_ROOT}/cluster/pulumi/sv-runbook/cometbft/priv_validator_key.json`,
  'utf-8'
);

export function installCometBftNode(
  xns: ExactNamespace,
  dependencies: Resource[]
): k8s.helm.v3.Release {
  new k8s.core.v1.Secret(
    'cometbft-keys',
    {
      metadata: {
        name: 'cometbft-keys',
        namespace: xns.logicalName,
      },
      type: 'Opaque',
      data: {
        'node_key.json': Buffer.from(nodeKeyContent).toString('base64'),
        'priv_validator_key.json': Buffer.from(privValidatorKeyContent).toString('base64'),
      },
    },
    { dependsOn: dependencies.concat([xns.ns]) }
  );
  return installCNSVHelmChart(
    xns,
    'cometbft',
    'cn-cometbft',
    _.mergeWith(cometBftValues, {
      node: {
        externalAddress: `cometbft.svc.${CLUSTER_BASENAME}.network.canton.global:26696`,
      },
      istioVirtualService: {
        enabled: true,
        gateway: 'cluster-ingress/cn-apps-gateway',
        port: 26696,
      },
      isDevNet: isDevNet,
    }),
    localCharts,
    version,
    dependencies
  );
}
