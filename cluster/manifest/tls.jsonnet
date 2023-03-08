local issuer(config) = {
  deploymentObjects: [
    {
      apiVersion: "cert-manager.io/v1",
      kind: "Issuer",
      metadata: {
        name: config.tls.issuerName,
      },
      spec: {
        acme: {
          server: config.tls.issuerServer,
          email: "team-canton-network@digitalasset.com",
          privateKeySecretRef: {
            name: config.tls.issuerName + "-acme-account",
          },
          solvers: [{
            dns01: {
              cloudDNS: {
                project: config.gcpDnsProject,
                serviceAccountSecretRef: {
                  name: config.gcpDnsSASecret,
                  key: "key.json",
                },
              },
            },
          }],
        },
      },
    },
  ],
};

local certificate(config, tlsCertSecret) = {
  deploymentObjects: [
    {
      apiVersion: "cert-manager.io/v1",
      kind: "Certificate",
      metadata: {
        name: config.clusterName + "-certificate",
        namespace: "default",
      },
      spec: {
        secretName: tlsCertSecret,
        issuerRef: {
          name: config.tls.issuerName,
        },
        dnsNames: [config.clusterDnsName, "*." + config.clusterDnsName, "*.validator1." + config.clusterDnsName, "*.splitwell." + config.clusterDnsName],
      },
    },
  ],
};

{
  issuer:: issuer,
  certificate:: certificate,
}
