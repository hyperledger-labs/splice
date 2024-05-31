// ensure the config is loaded and the ENV is overriden
import { config } from 'cn-pulumi-common';

import { clusterIsBeingReset, enableAlerts } from './alertings';
import { configureAuth0 } from './auth0';
import { clusterBasename } from './config';
import { installGcpLoggingAlerts } from './gcpAlerts';
import { configureIstio } from './istio';
import { configureNetwork } from './network';
import { configureObservability } from './observability';
import { configureStorage } from './storage';

const network = configureNetwork(clusterBasename);

export const ingressIp = network.ingressIp.address;
export const ingressNs = network.ingressNs.ns.metadata.name;
export const egressIp = network.egressIp.address;

const istio = configureIstio(network.ingressNs, ingressIp, network.publicIngressIp.address);

// Ensures that images required from Quay for observability can be pulled
const observabilityDependsOn = [network, istio];
configureObservability(observabilityDependsOn);
if (enableAlerts && !clusterIsBeingReset) {
  installGcpLoggingAlerts();
}

configureStorage();

let configuredAuth0;
if (config.envFlag('CLUSTER_CONFIGURE_AUTH0', true)) {
  configuredAuth0 = configureAuth0(clusterBasename, network.dnsNames);
}

export const auth0 = configuredAuth0;
