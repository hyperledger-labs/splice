// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
// ensure the config is loaded and the ENV is overriden
import { config } from '@lfdecentralizedtrust/splice-pulumi-common';

import { clusterIsResetPeriodically, enableAlerts } from './alertings';
import { configureAuth0 } from './auth0';
import { configureCloudArmorPolicy } from './cloudArmor';
import { cloudArmorConfig, clusterBaseDomain, clusterBasename, monitoringConfig } from './config';
import { installExtraCustomResources } from './extraCustomResources';
import {
  getNotificationChannel,
  installCloudSQLMaintenanceUpdateAlerts,
  installGcpLoggingAlerts,
  installClusterMaintenanceUpdateAlerts,
} from './gcpAlerts';
import { configureGKEL7Gateway } from './gcpLoadBalancer';
import { configureIstio, istioMonitoring } from './istio';
import { configureNetwork } from './network';
import { configureObservability } from './observability';
import { configureStorage } from './storage';

const network = configureNetwork(clusterBasename, clusterBaseDomain);

export const ingressIp = network.ingressIp.address;
export const ingressNs = network.ingressNs.ns.metadata.name;
export const egressIp = network.egressIp.address;

const cloudArmorSecurityPolicy = configureCloudArmorPolicy(cloudArmorConfig, network.ingressNs);

const istio = configureIstio(network.ingressNs, ingressIp, network.cometbftIngressIp.address);

const gcpGateway = configureGKEL7Gateway({
  ingressNs: network.ingressNs,
  securityPolicy: cloudArmorSecurityPolicy,
});

// Ensures that images required from Quay for observability can be pulled
const observabilityDependsOn = istio.concat([network]);
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

let configuredAuth0;
if (config.envFlag('CLUSTER_CONFIGURE_AUTH0', true)) {
  configuredAuth0 = configureAuth0(clusterBasename, network.dnsNames);
}

export const auth0 = configuredAuth0;
