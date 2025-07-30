// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as k8s from '@pulumi/kubernetes';
import * as pulumi from '@pulumi/pulumi';
import { dsoSize } from 'splice-pulumi-common-sv/src/dsoConfig';
import { cometBFTExternalPort } from 'splice-pulumi-common-sv/src/synchronizer/cometbftConfig';

import { isDevNet } from '../../common';
import { DecentralizedSynchronizerUpgradeConfig } from './domainMigration';
import { CLUSTER_HOSTNAME, ExactNamespace } from './utils';

export function installLoopback(namespace: ExactNamespace): pulumi.Resource[] {
  const numMigrations = DecentralizedSynchronizerUpgradeConfig.highestMigrationId + 1;
  // For DevNet-like clusters, we always assume at least 5 SVs to reduce churn on the gateway definition,
  // and support easily deploying without refreshing the infra stack.
  const numSVs = dsoSize < 5 && isDevNet ? 5 : dsoSize;

  const cometBFTPorts = Array.from({ length: numMigrations }, (_, i) => i).flatMap(migration =>
    Array.from({ length: numSVs }, (_, node) => node).map(node => ({
      number: cometBFTExternalPort(migration, node),
      name: `cometbft-${migration}-${node}-p2p`,
      protocol: 'TCP',
    }))
  );

  const clusterHostname = CLUSTER_HOSTNAME;
  const serviceEntry = new k8s.apiextensions.CustomResource(
    `loopback-service-entry-${namespace.logicalName}`,
    {
      apiVersion: 'networking.istio.io/v1alpha3',
      kind: 'ServiceEntry',
      metadata: {
        name: 'loopback',
        namespace: namespace.ns.metadata.name,
      },
      spec: {
        hosts: [clusterHostname],
        exportTo: ['.'],
        ports: [
          {
            number: 80,
            name: 'http-port',
            protocol: 'HTTP',
          },
          {
            number: 443,
            name: 'tls',
            protocol: 'TLS',
          },
          {
            number: 5008,
            name: 'grpc-domain',
            protocol: 'GRPC',
          },
        ].concat(cometBFTPorts),
        resolution: 'DNS',
      },
    },
    { dependsOn: [namespace.ns] }
  );

  const svHosts = Array.from({ length: numSVs }, (_, i) =>
    i == 0
      ? [`sv-2.${clusterHostname}`, `*.sv-2.${clusterHostname}`]
      : [`sv-${i + 1}-eng.${clusterHostname}`, `*.sv-${i + 1}-eng.${clusterHostname}`]
  ).flat();
  const allHosts = [
    clusterHostname,
    `sv.${clusterHostname}`,
    `*.sv.${clusterHostname}`,
    `validator.${clusterHostname}`,
    `*.validator.${clusterHostname}`,
    `validator1.${clusterHostname}`,
    `*.validator1.${clusterHostname}`,
    `splitwell.${clusterHostname}`,
    `*.splitwell.${clusterHostname}`,
  ].concat(svHosts);

  const virtualService = new k8s.apiextensions.CustomResource(
    `loopback-virtual-service-${namespace.logicalName}`,
    {
      apiVersion: 'networking.istio.io/v1alpha3',
      kind: 'VirtualService',
      metadata: {
        name: 'direct-loopback-through-ingress-gateway',
        namespace: namespace.ns.metadata.name,
      },
      spec: {
        hosts: allHosts,
        exportTo: ['.'],
        gateways: ['mesh'],
        http: [
          {
            match: [
              {
                gateways: ['mesh'],
              },
            ],
            route: [
              {
                destination: {
                  host: 'istio-ingress.cluster-ingress.svc.cluster.local',
                },
              },
            ],
          },
        ],
        tls: [
          {
            match: [
              {
                gateways: ['mesh'],
                sniHosts: allHosts,
              },
            ],
            route: [
              {
                destination: {
                  host: 'istio-ingress.cluster-ingress.svc.cluster.local',
                },
              },
            ],
          },
        ],
        tcp: [
          {
            match: [
              {
                gateways: ['mesh'],
              },
            ],
            route: [
              {
                destination: {
                  host: 'istio-ingress.cluster-ingress.svc.cluster.local',
                },
              },
            ],
          },
        ],
      },
    },
    { dependsOn: [namespace.ns] }
  );

  return [serviceEntry, virtualService];
}
