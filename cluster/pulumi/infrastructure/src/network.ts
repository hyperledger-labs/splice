import * as pulumi from "@pulumi/pulumi";
import * as gcp from "@pulumi/gcp";
import * as k8s from "@pulumi/kubernetes";

import * as certmanager from "@pulumi/kubernetes-cert-manager";

function clusterIpAddress(addressName: string): gcp.compute.Address {
  return new gcp.compute.Address(addressName, {
    name: addressName,
    networkTier: "PREMIUM",
  });
}

function clusterDnsEntries(
  clusterName: string,
  dnsName: string,
  clusterIp: gcp.compute.Address
): gcp.dns.RecordSet[] {
  return [
    new gcp.dns.RecordSet(dnsName, {
      name: dnsName + ".",
      ttl: 60,
      type: "A",
      project: process.env.GCP_DNS_PROJECT,
      managedZone: "canton-global",
      rrdatas: [clusterIp.address],
    }),
    new gcp.dns.RecordSet(dnsName + "-subdomains", {
      name: `*.${dnsName}.`,
      ttl: 60,
      type: "A",
      project: process.env.GCP_DNS_PROJECT,
      managedZone: "canton-global",
      rrdatas: [clusterIp.address],
    }),
  ];
}

function certManager(
  certManagerNamespaceName: string
): certmanager.CertManager {
  const ns = new k8s.core.v1.Namespace(certManagerNamespaceName, {
    metadata: {
      name: certManagerNamespaceName,
    },
  });

  return new certmanager.CertManager("cert-manager", {
    installCRDs: true,
    helmOptions: {
      namespace: ns.metadata.name,
    },
  });
}

function clusterCertificate(
  clusterName: string,
  dnsName: string,
  ns: k8s.core.v1.Namespace,
  manager: certmanager.CertManager,
  dnsEntries: gcp.dns.RecordSet[]
): k8s.apiextensions.CustomResource {
  const issuerName = "letsencrypt-production";
  const issuerServer = "https://acme-v02.api.letsencrypt.org/directory";

  const issuer = new k8s.apiextensions.CustomResource(
    "issuer",
    {
      apiVersion: "cert-manager.io/v1",
      kind: "Issuer",
      metadata: {
        name: issuerName,
        namespace: ns.metadata.name,
      },
      spec: {
        acme: {
          email: "team-canton-network@digitalasset.com",
          preferredChain: "",
          privateKeySecretRef: {
            name: `${issuerName}-acme-account`,
          },
          server: issuerServer,
          solvers: [
            {
              dns01: {
                cloudDNS: {
                  project: "da-gcp-canton-domain",
                  serviceAccountSecretRef: {
                    key: "key.json",
                    name: "clouddns-dns01-solver-svc-acct",
                  },
                },
              },
            },
          ],
        },
      },
    },
    {
      dependsOn: [manager],
    }
  );

  const config = new pulumi.Config();

  const dnsSaKey = new k8s.core.v1.Secret(
    "clouddns-dns01-solver-svc-acct",
    {
      metadata: {
        name: "clouddns-dns01-solver-svc-acct",
        namespace: ns.metadata.name,
      },
      type: "Opaque",
      data: {
        "key.json": config.require("DNS_SA_KEY"),
      },
    },
    {
      dependsOn: ns,
    }
  );

  return new k8s.apiextensions.CustomResource(
    "certificate",
    {
      apiVersion: "cert-manager.io/v1",
      kind: "Certificate",
      metadata: {
        name: `cn-${clusterName}-certificate`,
        namespace: ns.metadata.name,
      },
      spec: {
        dnsNames: [
          `${dnsName}`,
          `*.${dnsName}`,
          `*.validator1.${dnsName}`,
          `*.splitwell.${dnsName}`,
          `*.svc.${dnsName}`,
          `*.sv-1.svc.${dnsName}`,
          `*.sv-2.svc.${dnsName}`,
          `*.sv-3.svc.${dnsName}`,
          `*.sv-4.svc.${dnsName}`,
        ],
        issuerRef: {
          name: "letsencrypt-production",
        },
        secretName: `cn-${clusterName}net-tls`,
      },
    },
    {
      dependsOn: [...dnsEntries, issuer],
    }
  );
}

class CantonNetwork extends pulumi.ComponentResource {
  clusterIp: gcp.compute.Address;
  ingressNs: k8s.core.v1.Namespace;

  constructor(
    clusterName: string,
    opts: pulumi.ComponentResourceOptions | undefined = undefined
  ) {
    super("canton:gcp:CantonNetwork", clusterName, {}, opts);

    const dnsName = `${clusterName}.network.canton.global`;

    const clusterIp = clusterIpAddress(`cn-${clusterName}net-ip`);

    const certManagerDeployment = certManager("cert-manager");

    const dnsEntries = clusterDnsEntries(clusterName, dnsName, clusterIp);

    const ingressNs = new k8s.core.v1.Namespace("cluster-ingress", {
      metadata: {
        name: "cluster-ingress",
      },
    });

    clusterCertificate(
      clusterName,
      dnsName,
      ingressNs,
      certManagerDeployment,
      dnsEntries
    );

    this.clusterIp = clusterIp;
    this.ingressNs = ingressNs;

    this.registerOutputs();
  }
}

export function configureNetwork(clusterBasename: string): CantonNetwork {
  return new CantonNetwork(clusterBasename);
}
