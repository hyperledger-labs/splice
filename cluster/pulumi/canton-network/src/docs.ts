// Copyright (c) 2026 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as pulumi from '@pulumi/pulumi';
import {
  activeVersion,
  config,
  exactNamespace,
  imagePullSecret,
  installSpliceHelmChart,
} from '@lfdecentralizedtrust/splice-pulumi-common';

export function installDocs(): pulumi.Resource {
  const xns = exactNamespace('docs');

  const imagePullDeps = imagePullSecret(xns);

  const dependsOn = imagePullDeps.concat([xns.ns]);

  const networkName = config.requireEnv('GCP_CLUSTER_BASENAME').endsWith('zrh')
    ? config.requireEnv('GCP_CLUSTER_BASENAME').replace('zrh', '')
    : config.requireEnv('GCP_CLUSTER_BASENAME');

  return installSpliceHelmChart(
    xns,
    'docs',
    'cn-docs',
    {
      networkName: networkName,
      enableGcsProxy: true,
    },
    activeVersion,
    { dependsOn }
  );
}
