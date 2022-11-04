local certificateIssuer(issuerRef, issuerServer, cloudDnsProject, cloudDnsServiceAccount) = {
  deploymentObjects: [
    {
      apiVersion: 'cert-manager.io/v1',
      kind: 'Issuer',
      metadata: {
        name: issuerRef,
      },
      spec: {
        acme: {
          server: issuerServer,
          email: 'team-canton-network@digitalasset.com',
          privateKeySecretRef: {
            name: issuerRef + '-acme-account',
          },
          solvers: [{
            dns01: {
              cloudDNS: {
                project: cloudDnsProject,
                serviceAccountSecretRef: {
                  name: cloudDnsServiceAccount,
                  key: 'key.json',
                },
              },
            },
          }],
        },
      },
    },
  ],
};

local certificate(issuerRef, tlsCertSecret, clusterName, clusterDnsName) = {
  deploymentObjects: [
    {
      apiVersion: 'cert-manager.io/v1',
      kind: 'Certificate',
      metadata: {
        name: clusterName + '-certificate',
        namespace: 'default',
      },
      spec: {
        secretName: tlsCertSecret,
        issuerRef: {
          name: issuerRef,
        },
        dnsNames: [clusterDnsName, '*.' + clusterDnsName, '*.validator1.' + clusterDnsName],
      },
    },
  ],
};

{
    certificateIssuer:: certificateIssuer,
    certificate:: certificate
}
