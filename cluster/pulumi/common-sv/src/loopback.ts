// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
// This is part of `common-sv` to avoid circular dependencies between `common` and `common-sv`.
import * as k8s from '@pulumi/kubernetes';
import * as pulumi from '@pulumi/pulumi';
import { CLUSTER_HOSTNAME, ExactNamespace } from '@canton-network/splice-pulumi-common';

import { coreSvsToDeploy, svRunbookConfig } from './svConfigs';

export function installLoopback(namespace: ExactNamespace): pulumi.Resource[] {
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
        ],
        resolution: 'DNS',
      },
    },
    { dependsOn: [namespace.ns] }
  );

  // always excplitly include the runbook to avoid issues where the loopback will not work
  const svHosts = coreSvsToDeploy
    .concat([svRunbookConfig])
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

  return [serviceEntry, virtualService];
}
