// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
// This is part of `common-sv` to avoid circular dependencies between `common` and `common-sv`.
import * as k8s from '@pulumi/kubernetes';
import * as pulumi from '@pulumi/pulumi';
import {
  isDevNet,
  isMainNet,
  CLUSTER_HOSTNAME,
  DecentralizedSynchronizerUpgradeConfig,
  ExactNamespace,
} from '@lfdecentralizedtrust/splice-pulumi-common';

import { allSvsToDeploy, coreSvsToDeploy } from './svConfigs';
import { cometBFTExternalPort } from './synchronizer/cometbftConfig';

export function installSvLoopback(namespace: ExactNamespace): pulumi.Resource[] {
  return installLoopback(namespace, true);
}

export function installLoopback(
  namespace: ExactNamespace,
  cometbft: boolean = false
): pulumi.Resource[] {
  const numMigrations = DecentralizedSynchronizerUpgradeConfig.highestMigrationId + 1;
  // For DevNet-like clusters, we always assume at least 4 SVs (not including sv-runbook) to reduce churn on the gateway definition,
  // and support easily deploying without refreshing the infra stack.
  const numCoreSvsToDeploy = coreSvsToDeploy.length;
  const numSVs = numCoreSvsToDeploy < 4 && isDevNet ? 4 : numCoreSvsToDeploy;

  const cometBFTPorts = cometbft ? getCometBftPorts(numMigrations, numSVs) : [];

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

  const svHosts = allSvsToDeploy
    .map(sv => [`${sv.ingressName}.${clusterHostname}`, `*.${sv.ingressName}.${clusterHostname}`])
    .flat();
  const allHosts = [
    clusterHostname,
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
      },
    },
    { dependsOn: [namespace.ns] }
  );

  const cometBftVirtualService = cometbft
    ? [getCometBftVirtualService(namespace, clusterHostname)]
    : [];

  return [serviceEntry, virtualService, ...cometBftVirtualService];
}

// custom version of https://istio.io/latest/docs/reference/config/networking/service-entry/#ServicePort
type ServicePort = {
  number: number;
  name: string;
  protocol: string;
};

function getCometBftPorts(numMigrations: number, numSvs: number): ServicePort[] {
  const port = (migration: number, node: number) => ({
    number: cometBFTExternalPort(migration, node),
    name: `cometbft-${migration}-${node}-p2p`,
    protocol: 'TCP',
  });
  return Array.from({ length: numMigrations }, (_, i) => i).flatMap(migration => {
    const ret = Array.from({ length: numSvs }, (_, node) => node).map(node =>
      port(migration, node + 1)
    );
    if (!isMainNet) {
      // For non-mainnet clusters, include "node 0" for the sv runbook
      ret.unshift(port(migration, 0));
    }
    return ret;
  });
}

function getCometBftVirtualService(namespace: ExactNamespace, clusterHostname: string) {
  return new k8s.apiextensions.CustomResource(
    `loopback-cometbft-${namespace.logicalName}`,
    {
      apiVersion: 'networking.istio.io/v1alpha3',
      kind: 'VirtualService',
      metadata: {
        name: 'cometbft-loopback',
        namespace: namespace.ns.metadata.name,
      },
      spec: {
        // Even though we only use url `cometbft.clusterHostname`, for some reason setting that in the
        // hosts field here did not work, so we accept that cometbft is the only tcp traffic right now
        // anyway, and just use the base cluster hostname and route all tcp traffic through istio-ingress-cometbft.
        hosts: [clusterHostname],
        exportTo: ['.'],
        gateways: ['mesh'],
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
                  host: 'istio-ingress-cometbft.cluster-ingress.svc.cluster.local',
                },
              },
            ],
          },
        ],
      },
    },
    { dependsOn: [namespace.ns] }
  );
}
