import * as gcp from '@pulumi/gcp';
import * as k8s from '@pulumi/kubernetes';
import * as certmanager from '@pulumi/kubernetes-cert-manager';
import * as pulumi from '@pulumi/pulumi';
import { RecordSet } from '@pulumi/gcp/dns/recordSet';
import {
  btoa,
  config,
  exactNamespace,
  ExactNamespace,
  GCP_PROJECT,
  isMainNet,
  publicPrometheusRemoteWrite,
} from 'cn-pulumi-common';

import { gcpDnsProject } from './config';

function ipAddress(addressName: string): gcp.compute.Address {
  return new gcp.compute.Address(addressName, {
    name: addressName,
    networkTier: 'PREMIUM',
  });
}

function clusterDnsEntries(
  dnsName: string,
  managedZone: string,
  ingressIp: gcp.compute.Address,
  publicIngressIp?: gcp.compute.Address
): gcp.dns.RecordSet[] {
  return [
    new gcp.dns.RecordSet(dnsName, {
      name: dnsName + '.',
      ttl: 60,
      type: 'A',
      project: gcpDnsProject,
      managedZone: managedZone,
      rrdatas: [ingressIp.address],
    }),
    new gcp.dns.RecordSet(dnsName + '-subdomains', {
      name: `*.${dnsName}.`,
      ttl: 60,
      type: 'A',
      project: gcpDnsProject,
      managedZone: managedZone,
      rrdatas: [ingressIp.address],
    }),
  ].concat(
    publicIngressIp
      ? [
          new gcp.dns.RecordSet(dnsName + '-public', {
            name: `public.${dnsName}.`,
            ttl: 60,
            type: 'A',
            project: gcpDnsProject,
            managedZone: managedZone,
            rrdatas: [publicIngressIp.address],
          }),
        ]
      : []
  );
}

function certManager(certManagerNamespaceName: string): certmanager.CertManager {
  const ns = new k8s.core.v1.Namespace(certManagerNamespaceName, {
    metadata: {
      name: certManagerNamespaceName,
    },
  });

  return new certmanager.CertManager('cert-manager', {
    installCRDs: true,
    helmOptions: {
      namespace: ns.metadata.name,
    },
  });
}

function clusterCertificate(
  clusterName: string,
  dnsNames: string[],
  ns: k8s.core.v1.Namespace,
  manager: certmanager.CertManager,
  dnsEntries: gcp.dns.RecordSet[]
): k8s.apiextensions.CustomResource {
  const useStaging = config.envFlag('USE_LETSENCRYPT_STAGING', false);

  let issuerName, issuerServer;

  if (useStaging) {
    issuerName = 'letsencrypt-staging';
    issuerServer = 'https://acme-staging-v02.api.letsencrypt.org/directory';
  } else {
    issuerName = 'letsencrypt-production';
    issuerServer = 'https://acme-v02.api.letsencrypt.org/directory';
  }

  const issuer = new k8s.apiextensions.CustomResource(
    'issuer',
    {
      apiVersion: 'cert-manager.io/v1',
      kind: 'Issuer',
      metadata: {
        name: issuerName,
        namespace: ns.metadata.name,
      },
      spec: {
        acme: {
          email: 'team-canton-network@digitalasset.com',
          preferredChain: '',
          privateKeySecretRef: {
            name: `${issuerName}-acme-account`,
          },
          server: issuerServer,
          solvers: [
            {
              dns01: {
                cloudDNS: {
                  project: 'da-gcp-canton-domain',
                  serviceAccountSecretRef: {
                    key: 'key.json',
                    name: 'clouddns-dns01-solver-svc-acct',
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

  const gcpSecretName = config.requireEnv('DNS01_SA_KEY_SECRET');

  gcp.secretmanager.SecretVersion.get(
    'dns01-sa-key-secret',
    `projects/${GCP_PROJECT}/secrets/${gcpSecretName}/versions/1`
  ).secretData.apply(dns01SaKeySecret => {
    new k8s.core.v1.Secret(
      'clouddns-dns01-solver-svc-acct',
      {
        metadata: {
          name: 'clouddns-dns01-solver-svc-acct',
          namespace: ns.metadata.name,
        },
        type: 'Opaque',
        data: {
          // TODO(#9227): Handle this correctly in dump-config. Currently it gets here with an undefined value.
          'key.json': btoa(dns01SaKeySecret || 'dns-secret'),
        },
      },
      {
        dependsOn: ns,
      }
    );
  });

  const certDnsNames = dnsNames
    .map(dnsName => [
      `${dnsName}`,
      `*.${dnsName}`,
      `*.validator.${dnsName}`,
      `*.validator1.${dnsName}`,
      `*.splitwell.${dnsName}`,
      `*.${dnsName}`,
      `*.sv-2.${dnsName}`,
      `*.sv-2-eng.${dnsName}`,
      `*.sv-3-eng.${dnsName}`,
      `*.sv-4-eng.${dnsName}`,
      `*.sv-5-eng.${dnsName}`,
      `*.sv-6-eng.${dnsName}`,
      `*.sv-7-eng.${dnsName}`,
      `*.sv-8-eng.${dnsName}`,
      `*.sv-9-eng.${dnsName}`,
      `*.sv-10-eng.${dnsName}`,
      `*.sv-11-eng.${dnsName}`,
      `*.sv-12-eng.${dnsName}`,
      `*.sv-13-eng.${dnsName}`,
      `*.sv-14-eng.${dnsName}`,
      `*.sv-15-eng.${dnsName}`,
      `*.sv-16-eng.${dnsName}`,
      `*.sv.${dnsName}`,
    ])
    .flat();

  return new k8s.apiextensions.CustomResource(
    'certificate',
    {
      apiVersion: 'cert-manager.io/v1',
      kind: 'Certificate',
      metadata: {
        name: `cn-${clusterName}-certificate`,
        namespace: ns.metadata.name,
      },
      spec: {
        dnsNames: certDnsNames,
        issuerRef: {
          name: issuerName,
        },
        secretName: `cn-${clusterName}net-tls`,
      },
    },
    {
      dependsOn: [...dnsEntries, issuer],
    }
  );
}

const project = gcp.config.project;

function natGateway(
  clusterName: string,
  egressIp: gcp.compute.Address,
  options = {}
): gcp.compute.RouterNat {
  const privateNetwork = gcp.compute.Network.get(
    'default',
    `projects/${project}/global/networks/default`
  );

  const subnet = gcp.compute.getSubnetworkOutput({
    name: `cn-${clusterName}net-subnet`,
  });

  const router = new gcp.compute.Router(
    `router-${clusterName}`,
    {
      network: privateNetwork.id,
    },
    options
  );

  // Create a Cloud NAT gateway to configure the outbound IP address
  const natGateway = new gcp.compute.RouterNat(
    `nat-${clusterName}-gw`,
    {
      router: router.name,
      region: router.region,
      natIpAllocateOption: 'MANUAL_ONLY',
      natIps: [egressIp.selfLink],
      sourceSubnetworkIpRangesToNat: 'LIST_OF_SUBNETWORKS',
      subnetworks: [
        {
          name: subnet.id,
          sourceIpRangesToNats: ['ALL_IP_RANGES'],
        },
      ],
      logConfig: {
        enable: true,
        filter: 'ERRORS_ONLY',
      },
    },
    options
  );

  return natGateway;
}

class CantonNetwork extends pulumi.ComponentResource {
  ingressIp: gcp.compute.Address;
  publicIngressIp: gcp.compute.Address | undefined;
  egressIp: gcp.compute.Address;
  ingressNs: ExactNamespace;
  dnsNames: string[];

  constructor(clusterName: string, opts: pulumi.ComponentResourceOptions | undefined = undefined) {
    super('canton:gcp:CantonNetwork', clusterName, {}, opts);

    const ingressIp = ipAddress(`cn-${clusterName}net-ip`);

    const publicIngressIp = publicPrometheusRemoteWrite
      ? ipAddress(`cn-${clusterName}net-pub-ip`)
      : undefined;

    const egressIp = ipAddress(`cn-${clusterName}-out`);

    const certManagerDeployment = certManager('cert-manager');

    const ingressNs = exactNamespace('cluster-ingress');

    let cantonGlobalDnsName: string;
    let cantonGlobalDnsEntries: RecordSet[];

    let daDnsName: string;
    let daDnsEntries: RecordSet[];
    if (isMainNet) {
      cantonGlobalDnsName = `network.canton.global`;
      cantonGlobalDnsEntries = clusterDnsEntries(
        cantonGlobalDnsName,
        'canton-global',
        ingressIp,
        publicIngressIp
      );

      daDnsName = `global.canton.network.digitalasset.com`;
      daDnsEntries = clusterDnsEntries(daDnsName, 'prod-networks', ingressIp, publicIngressIp);
    } else {
      cantonGlobalDnsName = `${clusterName}.network.canton.global`;
      cantonGlobalDnsEntries = clusterDnsEntries(
        cantonGlobalDnsName,
        'canton-global',
        ingressIp,
        publicIngressIp
      );

      daDnsName = `${clusterName}.global.canton.network.digitalasset.com`;
      daDnsEntries = clusterDnsEntries(daDnsName, 'prod-networks', ingressIp, publicIngressIp);
    }

    this.dnsNames = [cantonGlobalDnsName, daDnsName];

    clusterCertificate(clusterName, this.dnsNames, ingressNs.ns, certManagerDeployment, [
      ...cantonGlobalDnsEntries,
      ...daDnsEntries,
    ]);

    natGateway(clusterName, egressIp, { parent: this });

    this.ingressIp = ingressIp;
    this.publicIngressIp = publicIngressIp;
    this.egressIp = egressIp;
    this.ingressNs = ingressNs;

    this.registerOutputs();
  }
}

export function configureNetwork(clusterBasename: string): CantonNetwork {
  return new CantonNetwork(clusterBasename);
}
