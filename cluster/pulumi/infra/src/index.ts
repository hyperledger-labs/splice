import { clusterBasename } from './config';
import { configureIstio } from './istio';
import { configureNetwork } from './network';

const network = configureNetwork(clusterBasename);

export const ingressIp = network.ingressIp.address;
export const ingressNs = network.ingressNs.metadata.name;
export const egressIp = network.egressIp.address;

configureIstio(network.ingressNs, ingressIp);
