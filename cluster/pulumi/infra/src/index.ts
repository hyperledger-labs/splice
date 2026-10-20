// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
// ensure the config is loaded and the ENV is overriden
import * as k8s from '@pulumi/kubernetes';
import * as pulumi from '@pulumi/pulumi';
import { config } from '@canton-network/splice-pulumi-common';
import { svsConfig } from '@canton-network/splice-pulumi-common-sv/src/config';
import { local } from '@pulumi/command';

import { configureSweet } from '../sweet';
import { configureAuth0 } from './auth0';
import { configureCloudArmorPolicy } from './cloudArmor';
import {
  cloudArmorConfig,
  clusterBaseDomain,
  clusterBasename,
  enableGCReaperJob,
  infraConfig,
} from './config';
import { installExtraCustomResources } from './extraCustomResources';
import { configureGKEL7Gateway } from './gcpLoadBalancer';
import { configureIstio, istioVersion } from './istio';
import { deployGCPodReaper } from './maintenance';
import { configureNetwork } from './network';
import { configureReloader } from './reloader';
import { sequencerP2pHosts } from './sequencerP2pHosts';
import { configureStorage } from './storage';

const network = configureNetwork(clusterBasename, clusterBaseDomain);

export const ingressIp = network.ingressIp.address;
export const ingressNs = network.ingressNs.ns.metadata.name;
export const egressIp = network.egressIp.address;

const cloudArmorSecurityPolicy = configureCloudArmorPolicy(
  cloudArmorConfig,
  svsConfig?.scan?.externalRateLimits || {}
);
const useGKEL7Gateway = infraConfig.gkeGateway.proxyForIstioHttp;

const gkeGatewayTeardown = !useGKEL7Gateway
  ? new local.Command('cn-gke-l7-gateway-teardown', {
      create: pulumi.interpolate`kubectl delete gateway cn-gke-l7-gateway -n ${network.ingressNs.ns.metadata.name} --ignore-not-found --wait=true`,
      triggers: [useGKEL7Gateway],
    })
  : undefined;

if (!useGKEL7Gateway && cloudArmorSecurityPolicy) {
  throw new Error(
    'Cloud Armor requires infra.gkeGateway.proxyForIstioHttp to be enabled to take effect'
  );
}

const istio = configureIstio(
  network.ingressNs,
  ingressIp,
  useGKEL7Gateway,
  gkeGatewayTeardown ? [gkeGatewayTeardown] : []
);

if (useGKEL7Gateway) {
  configureGKEL7Gateway({
    ingressNs: network.ingressNs,
    ingressAddress: network.ingressIp,
    gatewayName: 'cn-gke-l7-gateway',
    backendServiceName: istio.httpServiceName,
    serviceTarget: { port: 80 },
    tlsSecretName: `cn-${clusterBasename}net-tls`,
    securityPolicy: cloudArmorSecurityPolicy,
    backendLogging: cloudArmorConfig.logging,
    // The sequencer BFT P2P API is mutually authenticated gRPC between known peers, so
    // Cloud Armor adds no protection there while billing every P2P request. Route it to
    // a backend service without a security policy attached.
    cloudArmorExemptHostnames: sequencerP2pHosts(),
    istioResource: istio.istioResource,
  });
}

configureStorage();

configureReloader();

if (infraConfig.enableSweetSecurity) {
  configureSweet();
}

installExtraCustomResources();

if (enableGCReaperJob) {
  deployGCPodReaper('cluster-pod-gc-reaper', ['multi-validator'], { parent: network.ingressNs.ns });
}

let configuredAuth0;
if (config.envFlag('CLUSTER_CONFIGURE_AUTH0', true)) {
  configuredAuth0 = configureAuth0(clusterBasename, network.dnsNames);
}

export const auth0 = configuredAuth0;

export const istioDashboardVersions = istioVersion.dashboards;
// legacy while we migrate to the new observability project to avoid losing the prometheus data
const namespaceName = 'observability';
const namespace = new k8s.core.v1.Namespace(
  namespaceName,
  {
    metadata: {
      name: namespaceName,
      // istio really doesn't play well with prometheus
      // it seems to  modify the scraping calls from prometheus and change labels/include extra time series that make no sense
      labels: { 'istio-injection': 'disabled' },
    },
  },
  {
    aliases: [
      { name: 'observabilty' }, // Legacy typo
    ],
    // managed by the observability project
    retainOnDelete: true,
  }
);
