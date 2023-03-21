import * as gcp from "@pulumi/gcp";
import { CLUSTER_DNS_NAME, clusterIp } from "./utils";

function configureDNS() {
  return [
    new gcp.dns.RecordSet(CLUSTER_DNS_NAME, {
      name: CLUSTER_DNS_NAME + ".",
      ttl: 60,
      type: "A",
      project: process.env.GCP_DNS_PROJECT,
      managedZone: "canton-global",
      rrdatas: [clusterIp],
    }),

    new gcp.dns.RecordSet(CLUSTER_DNS_NAME + "-subdomains", {
      name: `*.${CLUSTER_DNS_NAME}.`,
      ttl: 60,
      type: "A",
      project: process.env.GCP_DNS_PROJECT,
      managedZone: "canton-global",
      rrdatas: [clusterIp],
    }),
  ];
}

// Cloud NAT parameters
// Required to allow cert - manager's pods to reach out to LetsEncrypt's API server and request certs
const GCP_NAT_ROUTER_NAME = "cert-manager-nat-router";
const GCP_NAT_CONFIG_NAME = `${GCP_NAT_ROUTER_NAME}-config`;

function configureNAT() {
  const router = new gcp.compute.Router(GCP_NAT_ROUTER_NAME, {
    name: GCP_NAT_ROUTER_NAME,
    network: "default",
  });

  return [
    router,
    new gcp.compute.RouterNat(GCP_NAT_CONFIG_NAME, {
      name: GCP_NAT_CONFIG_NAME,
      router: router.name,
      natIpAllocateOption: "AUTO_ONLY",
      sourceSubnetworkIpRangesToNat: "ALL_SUBNETWORKS_ALL_IP_RANGES",
    }),
  ];
}

export function configureNetwork() {
  configureDNS();
  configureNAT();
}
