import { configureNetwork } from "./network";
import * as pulumi from "@pulumi/pulumi";

const clusterBasename = pulumi.getStack().replace(/.*[.]/, "");

const network = configureNetwork(clusterBasename);

export const ingressIp = network.ingressIp.address;
export const ingressNs = network.ingressNs.metadata.name;
export const egressIp = network.egressIp.address;
