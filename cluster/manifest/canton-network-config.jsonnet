local networkDefaults = import './network-defaults.jsonnet';

local c = import './cluster.jsonnet';


// memoryLimitMiB values for deployments are taken emperically from
// DevNet with `kubectl top pod`. Note that these were taken on a very
// lightly loaded cluster and will very likely need to be revised for
// clusters with higher loads.

local svcDeployments(config) = [
  c.deployment(
    config,
    'canton-domain',
    [
      {
        name: 'cd-pub-api',
        port: 5008,
      },
      {
        name: 'cd-adm-api',
        port: 5009,
      },
    ],
    ext={
      readinessProbe: {
        tcpSocket: {
          port: 'cd-pub-api',
        },
      },
      livenessProbe: {
        tcpSocket: {
          port: 'cd-pub-api',
        },
        failureThreshold: 5,
        periodSeconds: 10,
      },
      startupProbe: {
        tcpSocket: {
          port: 'cd-pub-api',
        },
        failureThreshold: 20,
        periodSeconds: 10,
      },
    },
  ),

  c.deployment(config, 'canton-participant', [
    {
      name: 'cp-adm-api',
      port: 5002,
    },
    {
      name: 'cp-lg-api',
      port: 5001,
    },
  ], memoryLimitMiB=3072),

  c.deployment(config, 'directory-app', [
    {
      name: 'dir-api',
      port: 5010,
    },
  ], proxyToGrpcWeb='dir-api'),

  c.deployment(config, 'svc-app', [
    {
      name: 'svc-app-adm-api',
      port: 5005,
    },
  ]),

  c.deployment(config, 'scan-app', [
    {
      name: 'scan-api',
      port: 5012,
    },
  ], proxyToGrpcWeb='scan-api'),
];

local validator1Deployments(config) = [
  c.deployment(config, 'validator1-participant', [
    {
      name: 'val1-adm-api',
      port: 5102,
    },
    {
      name: 'val1-lg-api',
      port: 5101,
    },
  ], memoryLimitMiB=3072, proxyToGrpcWeb='val1-lg-api'),

  c.deployment(config, 'validator1-validator-app', [
    {
      name: 'val1-val-api',
      port: 5103,
    },
  ], proxyToGrpcWeb='val1-val-api'),

  c.deployment(config, 'validator1-wallet-app', [
    {
      name: 'val1-wal-api',
      port: 5104,
    },
  ], proxyToGrpcWeb='val1-wal-api'),

  c.deployment(config, 'validator1-wallet-web-ui', [
    {
      name: 'val1-wal-ui',
      port: 80,
      internalOnly: true,
    },
  ]),

  c.deployment(config, 'validator1-directory-web-ui', [
    {
      name: 'val1-dir-ui',
      port: 80,
      internalOnly: true,
    },
  ]),

  c.deployment(config, 'validator1-splitwise-web-ui', [
    {
      name: 'val1-sw-ui',
      port: 80,
      internalOnly: true,
    },
  ]),
];

local splitwiseDeployments(config) = [
  c.deployment(config, 'splitwise-participant', [
    {
      name: 'sw-adm-api',
      port: 5202,
    },
    {
      name: 'sw-lg-api',
      port: 5201,
    },
  ], memoryLimitMiB=3072, proxyToGrpcWeb='sw-lg-api'),

  c.deployment(config, 'splitwise-validator-app', [
    {
      name: 'sw-val-api',
      port: 5203,
    },
  ]),

  c.deployment(config, 'splitwise-app', [
    {
      name: 'sw-api',
      port: 5213,
    },
  ], proxyToGrpcWeb='sw-api'),
];

local cantonNetwork(config) =
  c.cluster(config, [
    c.deployment(config, 'docs', [
      {
        name: 'http',
        port: 80,
      },
      {
        name: 'https',
        port: 443,
      },
    ]),
    c.deployment(config, 'gcs-proxy', [
      {
        name: 'http',
        port: 8080,
        internalOnly: true,
      },
    ], memoryLimitMiB=512),

    svcDeployments(config),
    validator1Deployments(config),
    splitwiseDeployments(config),
  ]);

function(
  gcpRegion,
  gcpRepoName,
  gcpDnsProject,
  gcpDnsSvcAcct,
  imageTag,
  ipAddr,
  clusterName,
  clusterDnsName
) cantonNetwork(networkDefaults {
  gcpRegion: gcpRegion,
  gcpRepoName: gcpRepoName,
  gcpDnsProject: gcpDnsProject,
  gcpDnsSvcAcct: gcpDnsSvcAcct,
  imageTag: imageTag,
  ipAddr: ipAddr,
  clusterName: clusterName,
  clusterDnsName: clusterDnsName,
})
