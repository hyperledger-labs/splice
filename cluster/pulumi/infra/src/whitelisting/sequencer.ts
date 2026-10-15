// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as k8s from '@pulumi/kubernetes';
import * as pulumi from '@pulumi/pulumi';
import {
  CLUSTER_HOSTNAME,
  DecentralizedSynchronizerUpgradeConfig,
} from '@canton-network/splice-pulumi-common';
import { allSvsToDeployBasic } from '@canton-network/splice-pulumi-common-sv/src/svConfigsBasic';

import { sequencerP2pHosts } from '../sequencerP2pHosts';
import { loadIPRanges } from './ipRanges';
import { createIstioIpAllowPolicies, istioIngressSelector } from './policies';

export function configureSequencerWhitelist(
  namespace: k8s.core.v1.Namespace
): pulumi.Output<pulumi.Resource[]>[] {
  const dnsNames = [CLUSTER_HOSTNAME];
  const migrations = DecentralizedSynchronizerUpgradeConfig.runningMigrations();

  const publicApiHosts = allSvsToDeployBasic.flatMap(sv =>
    migrations.flatMap(migration =>
      dnsNames.flatMap(dns => [
        `sequencer-${migration.id}.${sv.ingressName}.${dns}`,
        `sequencer-${migration.id}.${sv.ingressName}.${dns}:*`,
      ])
    )
  );
  const p2pHosts = sequencerP2pHosts().flatMap(host => [host, `${host}:*`]);

  const policies = [
    createIstioIpAllowPolicies({
      namePrefix: 'sequencer-pub-ip-whitelist',
      namespace: namespace.metadata.name,
      selector: istioIngressSelector,
      ipRanges: loadIPRanges(),
      to: [{ operation: { hosts: publicApiHosts } }],
    }),
  ];
  if (p2pHosts.length > 0) {
    policies.push(
      createIstioIpAllowPolicies({
        namePrefix: 'sequencer-p2p-ip-whitelist',
        namespace: namespace.metadata.name,
        selector: istioIngressSelector,
        ipRanges: loadIPRanges(true),
        to: [{ operation: { hosts: p2pHosts } }],
      })
    );
  }
  return policies;
}
