import * as pulumi from "@pulumi/pulumi";

import { configureNetwork } from "./network";

const clusterBasename = pulumi.getStack().replace(/.*[.]/, "");

const network = configureNetwork(clusterBasename);

export const clusterIp = network.clusterIp.address;
export const ingressNs = network.ingressNs.metadata.name;
