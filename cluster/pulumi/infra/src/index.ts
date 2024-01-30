import { configureAuth0 } from './auth0';
import { clusterBasename } from './config';
import { configureIstio } from './istio';
import { configureNetwork } from './network';
import { configureObservability } from './observability';
import { configureStorage } from './storage';

const network = configureNetwork(clusterBasename);

export const ingressIp = network.ingressIp.address;
export const publicIngressIp = network.publicIngressIp?.address;
export const ingressNs = network.ingressNs.metadata.name;
export const egressIp = network.egressIp.address;

configureIstio(network.ingressNs, ingressIp, publicIngressIp);

// Ensures that images required from Quay for observability can be pulled
const observabilityDependsOn = [network];
configureObservability(observabilityDependsOn);

configureStorage();

export const auth0 = configureAuth0();
