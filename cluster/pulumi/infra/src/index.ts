// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
// ensure the config is loaded and the ENV is overriden
import { config } from '@lfdecentralizedtrust/splice-pulumi-common';

import { clusterIsResetPeriodically, enableAlerts } from './alertings';
import { configureAuth0 } from './auth0';
import { configureCloudArmorPolicy } from './cloudArmor';
import {
  cloudArmorConfig,
  clusterBaseDomain,
  clusterBasename,
  enableGCReaperJob,
  infraConfig,
  monitoringConfig,
} from './config';
import { installExtraCustomResources } from './extraCustomResources';
import {
  getNotificationChannel,
  installCloudSQLMaintenanceUpdateAlerts,
  installGcpLoggingAlerts,
  installClusterMaintenanceUpdateAlerts,
} from './gcpAlerts';
import { configureGKEL7Gateway } from './gcpLoadBalancer';
import { configureIstio, istioMonitoring } from './istio';
import { deployGCPodReaper } from './maintenance';
import { configureNetwork } from './network';
import { configureObservability } from './observability';
import { configureStorage } from './storage';

const network = configureNetwork(clusterBasename, clusterBaseDomain);

export const ingressIp = network.ingressIp.address;
export const ingressNs = network.ingressNs.ns.metadata.name;
export const egressIp = network.egressIp.address;

const cloudArmorSecurityPolicy = configureCloudArmorPolicy(cloudArmorConfig);
const useGKEL7Gateway = infraConfig.gkeGateway.proxyForIstioHttp;

if (!useGKEL7Gateway && cloudArmorSecurityPolicy) {
  throw new Error(
    'Cloud Armor requires infra.gkeGateway.proxyForIstioHttp to be enabled to take effect'
  );
}

const istio = configureIstio(
  network.ingressNs,
  ingressIp,
  network.cometbftIngressIp.address,
  useGKEL7Gateway
);

if (useGKEL7Gateway) {
  configureGKEL7Gateway({
    ingressNs: network.ingressNs,
    ingressAddress: network.ingressIp,
    gatewayName: 'cn-gke-l7-gateway',
    backendServiceName: istio.httpServiceName,
    serviceTarget: { port: 443 }, // see configureGateway for why 443
    tlsSecretName: `cn-${clusterBasename}net-tls`,
    securityPolicy: cloudArmorSecurityPolicy,
    istioResource: istio.istioResource,
  });
}

// Ensures that images required from Quay for observability can be pulled
const observabilityDependsOn = istio.allResources.concat([network]);
configureObservability(observabilityDependsOn);
if (enableAlerts && !clusterIsResetPeriodically) {
  const notificationChannel = getNotificationChannel();
  if (notificationChannel) {
    installGcpLoggingAlerts(notificationChannel);
    installClusterMaintenanceUpdateAlerts(notificationChannel);
    if (monitoringConfig.alerting.alerts.cloudSql.maintenance) {
      installCloudSQLMaintenanceUpdateAlerts(notificationChannel);
    }
  }
}
istioMonitoring(network.ingressNs, []);

configureStorage();

installExtraCustomResources();

if (enableGCReaperJob) {
  deployGCPodReaper('cluster-pod-gc-reaper', ['multi-validator'], { parent: network.ingressNs.ns });
}

let configuredAuth0;
if (config.envFlag('CLUSTER_CONFIGURE_AUTH0', true)) {
  configuredAuth0 = configureAuth0(clusterBasename, network.dnsNames);
}

export const auth0 = configuredAuth0;
