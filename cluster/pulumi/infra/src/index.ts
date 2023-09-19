import { clusterBasename } from './config';
import { configureIstio } from './istio';
import { configureNetwork } from './network';
import { configureObservability } from './observability';

const network = configureNetwork(clusterBasename);

export const ingressIp = network.ingressIp.address;
export const ingressNs = network.ingressNs.metadata.name;
export const egressIp = network.egressIp.address;

configureIstio(network.ingressNs, ingressIp);

// Ensures that images required from Quay for observability can be pulled
const observabilityDependsOn = [network];
configureObservability(observabilityDependsOn);
