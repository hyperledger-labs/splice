import { configureNetwork } from "./network";
import * as pulumi from "@pulumi/pulumi";

const clusterBasename = pulumi.getStack().replace(/.*[.]/, "");

const network = configureNetwork(clusterBasename);

export const clusterIp = network.clusterIp.address;
export const ingressNs = network.ingressNs.metadata.name;
export const eipaddr = network.externalIp.address;
