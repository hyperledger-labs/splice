import { clusterBasename } from './config';
import { configureIstio } from './istio';
import { configureNetwork } from './network';
import { dnsServiceAccountKey } from './secrets';

const network = configureNetwork(clusterBasename);

export const ingressIp = network.ingressIp.address;
export const ingressNs = network.ingressNs.metadata.name;
export const egressIp = network.egressIp.address;

configureIstio(network.ingressNs, ingressIp);

export const dnsPrivateKey = dnsServiceAccountKey.privateKey;
export const dnsPrivateKeyId = dnsServiceAccountKey.id.apply(id => id.replace(/.*\//, ''));
