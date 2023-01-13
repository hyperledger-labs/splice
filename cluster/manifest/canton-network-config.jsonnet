local networkDefaults = import './network-defaults.jsonnet';

local c = import './cluster.jsonnet';

// A dictionary of all auth-related environment variables.
// Some variables are used by multiple apps.
local authEnvVars = std.foldl(function(prev, el) prev + c.authEnvVars(el), [
  { env: 'CN_APP_VALIDATOR_LEDGER_API_AUTH', secret: 'cn-app-validator-ledger-api-auth' },
  { env: 'CN_APP_WALLET_LEDGER_API_AUTH', secret: 'cn-app-wallet-ledger-api-auth' },
  { env: 'CN_APP_SVC_LEDGER_API_AUTH', secret: 'cn-app-svc-ledger-api-auth' },
  { env: 'CN_APP_SCAN_LEDGER_API_AUTH', secret: 'cn-app-scan-ledger-api-auth' },
  { env: 'CN_APP_DIRECTORY_LEDGER_API_AUTH', secret: 'cn-app-directory-ledger-api-auth' },
  { env: 'CN_APP_SPLITWISE_LEDGER_API_AUTH', secret: 'cn-app-splitwise-ledger-api-auth' },
  { env: 'CN_APP_SPLITWISE_VALIDATOR_LEDGER_API_AUTH', secret: 'cn-app-splitwise-validator-ledger-api-auth' },
], {});

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
      {
        name: 'cd-metrics',
        port: 10013,
        externalPort: 10313,
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
    memoryLimitMiB=config.domainMemoryMib
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
    {
      name: 'cp-metrics',
      port: 10013,
      externalPort: 10013,
    },
  ], memoryLimitMiB=config.participantMemoryMib, extraEnvVars=[
    authEnvVars.CN_APP_SVC_LEDGER_API_AUTH_USER_NAME,
    authEnvVars.CN_APP_SCAN_LEDGER_API_AUTH_USER_NAME,
    authEnvVars.CN_APP_DIRECTORY_LEDGER_API_AUTH_USER_NAME,
  ]),

  c.deployment(config, 'directory-app', [
    {
      name: 'dir-api',
      port: 5010,
    },
    {
      name: 'dir-http-api',
      port: 6010,
    },
  ], extraEnvVars=[
    authEnvVars.CN_APP_DIRECTORY_LEDGER_API_AUTH_URL,
    authEnvVars.CN_APP_DIRECTORY_LEDGER_API_AUTH_CLIENT_ID,
    authEnvVars.CN_APP_DIRECTORY_LEDGER_API_AUTH_CLIENT_SECRET,
    authEnvVars.CN_APP_DIRECTORY_LEDGER_API_AUTH_USER_NAME,
  ]),

  c.deployment(config, 'svc-app', [
    {
      name: 'svc-app-adm-api',
      port: 5005,
    },
  ], extraEnvVars=[
    authEnvVars.CN_APP_SVC_LEDGER_API_AUTH_URL,
    authEnvVars.CN_APP_SVC_LEDGER_API_AUTH_CLIENT_ID,
    authEnvVars.CN_APP_SVC_LEDGER_API_AUTH_CLIENT_SECRET,
    authEnvVars.CN_APP_SVC_LEDGER_API_AUTH_USER_NAME,
  ]),

  c.deployment(config, 'scan-app', [
    {
      name: 'scan-api',
      port: 5012,
    },
  ], proxyToGrpcWeb='scan-api', extraEnvVars=[
    authEnvVars.CN_APP_SCAN_LEDGER_API_AUTH_URL,
    authEnvVars.CN_APP_SCAN_LEDGER_API_AUTH_CLIENT_ID,
    authEnvVars.CN_APP_SCAN_LEDGER_API_AUTH_CLIENT_SECRET,
    authEnvVars.CN_APP_SCAN_LEDGER_API_AUTH_USER_NAME,
  ]),
];

local validator1Deployments(config) = [
  c.deployment(config, 'validator1-participant', [
    {
      name: 'val1-adm-api',
      port: 5002,
      externalPort: 5102,
    },
    {
      name: 'val1-lg-api',
      port: 5001,
      externalPort: 5101,
    },
    {
      name: 'val1-metrics',
      port: 10013,
      externalPort: 10113,
    },
  ], memoryLimitMiB=config.participantMemoryMib, proxyToGrpcWeb='val1-lg-api', extraEnvVars=[
    authEnvVars.CN_APP_WALLET_LEDGER_API_AUTH_USER_NAME,
    authEnvVars.CN_APP_VALIDATOR_LEDGER_API_AUTH_USER_NAME,
  ]),

  c.deployment(config, 'validator1-validator-app', [
    {
      name: 'val1-val-api',
      port: 5103,
    },
    {
      name: 'val1-val-http',
      port: 7103,
    },
  ], proxyToGrpcWeb='val1-val-api', extraEnvVars=[
    authEnvVars.CN_APP_VALIDATOR_LEDGER_API_AUTH_URL,
    authEnvVars.CN_APP_VALIDATOR_LEDGER_API_AUTH_CLIENT_ID,
    authEnvVars.CN_APP_VALIDATOR_LEDGER_API_AUTH_CLIENT_SECRET,
    authEnvVars.CN_APP_VALIDATOR_LEDGER_API_AUTH_USER_NAME,
    authEnvVars.CN_APP_WALLET_LEDGER_API_AUTH_USER_NAME,
  ]),

  c.deployment(config, 'validator1-wallet-app', [
    {
      name: 'val1-wal-api',
      port: 5104,
    },
  ], proxyToGrpcWeb='val1-wal-api', extraEnvVars=[
    authEnvVars.CN_APP_WALLET_LEDGER_API_AUTH_URL,
    authEnvVars.CN_APP_WALLET_LEDGER_API_AUTH_CLIENT_ID,
    authEnvVars.CN_APP_WALLET_LEDGER_API_AUTH_CLIENT_SECRET,
    authEnvVars.CN_APP_WALLET_LEDGER_API_AUTH_USER_NAME,
  ]),

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
      port: 5002,
      externalPort: 5202,
    },
    {
      name: 'sw-lg-api',
      port: 5001,
      externalPort: 5201,
    },
    {
      name: 'sw-metrics',
      port: 10013,
      externalPort: 10213,
    },
  ], memoryLimitMiB=config.participantMemoryMib, proxyToGrpcWeb='sw-lg-api', extraEnvVars=[
    authEnvVars.CN_APP_SPLITWISE_VALIDATOR_LEDGER_API_AUTH_USER_NAME,
  ]),

  c.deployment(config, 'splitwise-validator-app', [
    {
      name: 'sw-val-api',
      port: 5203,
    },
  ], extraEnvVars=[
    authEnvVars.CN_APP_SPLITWISE_VALIDATOR_LEDGER_API_AUTH_URL,
    authEnvVars.CN_APP_SPLITWISE_VALIDATOR_LEDGER_API_AUTH_CLIENT_ID,
    authEnvVars.CN_APP_SPLITWISE_VALIDATOR_LEDGER_API_AUTH_CLIENT_SECRET,
    authEnvVars.CN_APP_SPLITWISE_VALIDATOR_LEDGER_API_AUTH_USER_NAME,
    authEnvVars.CN_APP_SPLITWISE_LEDGER_API_AUTH_USER_NAME,
  ]),

  c.deployment(config, 'splitwise-app', [
    {
      name: 'sw-api',
      port: 5213,
    },
  ], proxyToGrpcWeb='sw-api', extraEnvVars=[
    authEnvVars.CN_APP_SPLITWISE_LEDGER_API_AUTH_URL,
    authEnvVars.CN_APP_SPLITWISE_LEDGER_API_AUTH_CLIENT_ID,
    authEnvVars.CN_APP_SPLITWISE_LEDGER_API_AUTH_CLIENT_SECRET,
    authEnvVars.CN_APP_SPLITWISE_LEDGER_API_AUTH_USER_NAME,
  ]),
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
  gcpDnsSASecret,
  imageTag,
  ipAddr,
  clusterName,
  clusterDnsName
) cantonNetwork(networkDefaults {
  gcpRegion: gcpRegion,
  gcpRepoName: gcpRepoName,
  gcpDnsProject: gcpDnsProject,
  gcpDnsSASecret: gcpDnsSASecret,
  imageTag: imageTag,
  ipAddr: ipAddr,
  clusterName: clusterName,
  clusterDnsName: clusterDnsName,
})
