import * as pulumi from "@pulumi/pulumi";
import * as gcp from "@pulumi/gcp";

// statically assigned addresses from existing resources imported
// using `pulumi import gcp:compute/address:Address NAME ID`
const ipAddress = process.env.GCP_NETWORK_IP;

const CLUSTER_BASENAME = pulumi.getStack().replace(/.*[.]/, '');
const CLUSTER_DNS_NAME = `${CLUSTER_BASENAME}.network.canton.global`;

class CantonNetwork extends pulumi.ComponentResource {
  constructor(
    name: string,
    opts: pulumi.ComponentResourceOptions | undefined = undefined
  ) {
    super("canton:gcp:CantonNetwork", name, {}, opts);

    const addressName = `cn-${name}net-ip`;
    const clusterAddress = new gcp.compute.Address(
      addressName,
      {
        name: addressName,
        address: ipAddress,
        networkTier: "PREMIUM",
      },
      { protect: true }
    );

    new gcp.dns.RecordSet(CLUSTER_DNS_NAME, {
      name: CLUSTER_DNS_NAME + ".",
      ttl: 60,
      type: "A",
      project: process.env.GCP_DNS_PROJECT,
      managedZone: "canton-global",
      rrdatas: [clusterAddress.address],
    }, {
      protect: true,
    });
    new gcp.dns.RecordSet(
      CLUSTER_DNS_NAME + "-subdomains",
      {
        name: `*.${CLUSTER_DNS_NAME}.`,
        ttl: 60,
        type: "A",
        project: process.env.GCP_DNS_PROJECT,
        managedZone: "canton-global",
        rrdatas: [clusterAddress.address],
      },
      { protect: true }
    );
    this.registerOutputs({
      clusterIp: clusterAddress,
    });
  }
}

export function configureNetwork(): void {
  new CantonNetwork(CLUSTER_BASENAME);
}
