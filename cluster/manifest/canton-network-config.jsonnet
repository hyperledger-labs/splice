local networkDefaults = import "./network-defaults.jsonnet";

local postgres = import "./postgres.jsonnet";

local c = import "./cluster.jsonnet";

local svcDeployments(config) = [
  postgres.database("postgres", config),
  c.deployment(
    config,
    "global-domain",
    [
      {
        name: "cd-pub-api",
        port: 5008,
      },
      {
        name: "cd-adm-api",
        port: 5009,
      },
      {
        name: "cd-metrics",
        port: 10013,
        externalPort: 10313,
      },
    ],
    image="canton-domain",
    ext={
      readinessProbe: {
        tcpSocket: {
          port: "cd-pub-api",
        },
      },
      livenessProbe: {
        tcpSocket: {
          port: "cd-pub-api",
        },
        failureThreshold: 5,
        periodSeconds: 10,
      },
      startupProbe: {
        tcpSocket: {
          port: "cd-pub-api",
        },
        failureThreshold: 20,
        periodSeconds: 10,
      },
    },
    cpuRequest=config.domainCpu,
    memoryLimitMiB=config.domainMemoryMib,
    extraEnvVars=[
      { name: "CANTON_DOMAIN_POSTGRES_SERVER", value: "postgres" },
    ]
  ),
  c.deployment(
    config,
    "splitwell-domain",
    [
      {
        name: "swd-pub-api",
        port: 5008,
        externalPort: 5108,
      },
      {
        name: "swd-adm-api",
        port: 5009,
        externalPort: 5109,
      },
      {
        name: "swd-metrics",
        port: 10013,
        externalPort: 10413,
      },
    ],
    image="canton-domain",
    ext={
      readinessProbe: {
        tcpSocket: {
          port: "swd-pub-api",
        },
      },
      livenessProbe: {
        tcpSocket: {
          port: "swd-pub-api",
        },
        failureThreshold: 5,
        periodSeconds: 10,
      },
      startupProbe: {
        tcpSocket: {
          port: "swd-pub-api",
        },
        failureThreshold: 20,
        periodSeconds: 10,
      },
    },
    cpuRequest=config.domainCpu,
    memoryLimitMiB=config.domainMemoryMib,
    extraEnvVars=[
      { name: "CANTON_DOMAIN_POSTGRES_SERVER", value: "sw-postgres" },
    ]
  ),

  c.deployment(config, "svc-participant", [
    {
      name: "svcp-adm-api",
      port: 5002,
    },
    {
      name: "svcp-lg-api",
      port: 5001,
    },
    {
      name: "svcp-metrics",
      port: 10013,
      externalPort: 10013,
    },
  ], image="canton-participant", cpuRequest=config.participantCpu, memoryLimitMiB=config.participantMemoryMib, extraEnvVars=
               c.appUserNameEnvBindings(["svc", "sv1", "sv2", "sv3", "sv4", "scan", "directory"]) + [
    { name: "CANTON_PARTICIPANT_POSTGRES_SERVER", value: "postgres" },
    { name: "CANTON_PARTICIPANT_POSTGRES_SCHEMA", value: "cn_participant" },
    { name: "CANTON_PARTICIPANT_USERS", json: [
      {
        name: { env: "CN_APP_SVC_LEDGER_API_AUTH_USER_NAME" },
        primaryParty: { allocate: "svc_party" },
        actAs: [{ fromUser: "self" }],
        readAs: [],
        admin: true,
      },
      {
        name: { env: "CN_APP_SCAN_LEDGER_API_AUTH_USER_NAME" },
        primaryParty: { fromUser: { env: "CN_APP_SVC_LEDGER_API_AUTH_USER_NAME" } },
        actAs: [],
        readAs: [{ fromUser: { env: "CN_APP_SVC_LEDGER_API_AUTH_USER_NAME" } }],
        admin: false,
      },
      {
        name: { env: "CN_APP_DIRECTORY_LEDGER_API_AUTH_USER_NAME" },
        primaryParty: { fromUser: { env: "CN_APP_SVC_LEDGER_API_AUTH_USER_NAME" } },
        actAs: [{ fromUser: { env: "CN_APP_SVC_LEDGER_API_AUTH_USER_NAME" } }],
        readAs: [],
        admin: true,
      },
      {
        name: { env: "CN_APP_SV1_LEDGER_API_AUTH_USER_NAME" },
        primaryParty: { allocate: "sv1" },
        actAs: [{ fromUser: "self" }, { fromUser: { env: "CN_APP_SVC_LEDGER_API_AUTH_USER_NAME" } }],
        readAs: [],
        admin: true,
      },
      {
        name: { env: "CN_APP_SV2_LEDGER_API_AUTH_USER_NAME" },
        primaryParty: { allocate: "sv2" },
        actAs: [{ fromUser: "self" }, { fromUser: { env: "CN_APP_SVC_LEDGER_API_AUTH_USER_NAME" } }],
        readAs: [],
        admin: true,
      },
      {
        name: { env: "CN_APP_SV3_LEDGER_API_AUTH_USER_NAME" },
        primaryParty: { allocate: "sv3" },
        actAs: [{ fromUser: "self" }, { fromUser: { env: "CN_APP_SVC_LEDGER_API_AUTH_USER_NAME" } }],
        readAs: [],
        admin: true,
      },
      {
        name: { env: "CN_APP_SV4_LEDGER_API_AUTH_USER_NAME" },
        primaryParty: { allocate: "sv4" },
        actAs: [{ fromUser: "self" }, { fromUser: { env: "CN_APP_SVC_LEDGER_API_AUTH_USER_NAME" } }],
        readAs: [],
        admin: true,
      },

    ] },
  ]),

  c.deployment(config, "directory-app", [
    {
      name: "dir-api",
      port: 5010,
    },
    {
      name: "dir-http-api",
      port: 6010,
    },
  ], extraEnvVars=c.appAuthEnvBinding("directory")),

  c.deployment(config, "svc-app", [
    {
      name: "svc-app-adm-api",
      port: 5005,
    },
  ], extraEnvVars=c.appAuthEnvBinding("svc")),

  c.deployment(config, "sv-app-1", [
    {
      name: "sv1-adm-api",
      port: 5014,
    },
  ], image="sv-app", extraEnvVars=c.appAuthEnvBinding("sv1", "sv") + [
    { name: "CN_APP_SV_ADMIN_API_PORT", value: "5014" },
    { name: "CN_APP_SV_FOUND_CONSORTIUM", value: "true" },
    { name: "CN_APP_SV_IS_DEV_NET", value: "true" },
  ]),

  c.deployment(config, "sv-app-2", [
    {
      name: "sv2-adm-api",
      port: 5015,
    },
  ], image="sv-app", extraEnvVars=c.appAuthEnvBinding("sv2", "sv") + [
    { name: "CN_APP_SV_ADMIN_API_PORT", value: "5015" },
    { name: "CN_APP_SV_IS_DEV_NET", value: "true" },
  ]),

  c.deployment(config, "sv-app-3", [
    {
      name: "sv3-adm-api",
      port: 5016,
    },
  ], image="sv-app", extraEnvVars=c.appAuthEnvBinding("sv3", "sv") + [
    { name: "CN_APP_SV_ADMIN_API_PORT", value: "5016" },
    { name: "CN_APP_SV_IS_DEV_NET", value: "true" },
  ]),

  c.deployment(config, "sv-app-4", [
    {
      name: "sv4-adm-api",
      port: 5017,
    },
  ], image="sv-app", extraEnvVars=c.appAuthEnvBinding("sv4", "sv") + [
    { name: "CN_APP_SV_ADMIN_API_PORT", value: "5017" },
    { name: "CN_APP_SV_IS_DEV_NET", value: "true" },
  ]),

  c.deployment(config, "scan-app", [
    {
      name: "scan-api",
      port: 5012,
    },
    {
      name: "scan-api-http",
      port: 6012,
    },
  ], extraEnvVars=c.appAuthEnvBinding("scan")),
];

local validator1Deployments(config) = [
  postgres.database("val1-postgres", config),
  c.deployment(config, "validator1-participant", [
    {
      name: "val1-adm-api",
      port: 5002,
      externalPort: 5102,
    },
    {
      name: "val1-lg-api",
      port: 5001,
      externalPort: 5101,
    },
    {
      name: "val1-metrics",
      port: 10013,
      externalPort: 10113,
    },
  ], image="canton-participant", cpuRequest=config.participantCpu, memoryLimitMiB=config.participantMemoryMib, proxyToGrpcWeb="val1-lg-api", extraEnvVars=c.appUserNameEnvBinding("validator") + [
    { name: "CANTON_PARTICIPANT_POSTGRES_SERVER", value: "val1-postgres" },
    { name: "CANTON_PARTICIPANT_POSTGRES_SCHEMA", value: "val1_participant" },
    { name: "CANTON_PARTICIPANT_USERS", json: [
      {
        name: { env: "CN_APP_VALIDATOR_LEDGER_API_AUTH_USER_NAME" },
        primaryParty: { allocate: "validator1_validator_service_user" },
        actAs: [{ fromUser: "self" }],
        readAs: [],
        admin: true,
      },
    ] },
    { name: "CANTON_PARTICIPANT_EXTRA_DOMAINS", json: [
      {
        alias: "splitwell",
        url: "http://splitwell-domain:5008",
      },
    ] },
  ]),

  c.deployment(config, "validator1-validator-app", [
    {
      name: "val1-val-http",
      port: 6103,
    },
  ], extraEnvVars=c.appAuthEnvBinding("validator") + c.appUserNameEnvBinding("wallet") + [{ name: "CN_APP_VALIDATOR_WALLET_USER_NAME", value: "auth0|63e3d75ff4114d87a2c1e4f5" }]),

  c.deployment(config, "validator1-wallet-app", [
    {
      name: "val1-wal-http",
      port: 6004,
    },
  ], image="wallet-app", extraEnvVars=c.appAuthEnvBinding("wallet") + [
    { name: "CN_APP_WALLET_PARTICIPANT_ADDRESS", value: "validator1-participant" },
    { name: "CN_APP_WALLET_VALIDATOR_ADDRESS", value: "validator1-validator-app" },
    { name: "CN_APP_WALLET_VALIDATOR_GRPC_PORT", value: "5103" },
    { name: "CN_APP_WALLET_VALIDATOR_HTTP_PORT", value: "6103" },
  ]),

  c.deployment(config, "validator1-wallet-web-ui", [
    {
      name: "val1-wal-ui",
      port: 80,
      internalOnly: true,
    },
  ], image="wallet-web-ui", cpuRequest=0.5, extraEnvVars=[
    { name: "CN_APP_WALLET_UI_AUTH_CLIENT_ID", value: "5RJeTm41IwUs8VbbnZHxFEPjCX5ojfaK" },
  ]),

  c.deployment(config, "validator1-directory-web-ui", [
    {
      name: "val1-dir-ui",
      port: 80,
      internalOnly: true,
    },
  ], cpuRequest=0.5),

  c.deployment(config, "validator1-splitwell-web-ui", [
    {
      name: "val1-sw-ui",
      port: 80,
      internalOnly: true,
    },
  ], cpuRequest=0.5),
];

local splitwellDeployments(config) = [
  postgres.database("sw-postgres", config),
  c.deployment(config, "splitwell-participant", [
    {
      name: "sw-adm-api",
      port: 5002,
      externalPort: 5202,
    },
    {
      name: "sw-lg-api",
      port: 5001,
      externalPort: 5201,
    },
    {
      name: "sw-metrics",
      port: 10013,
      externalPort: 10213,
    },
  ], image="canton-participant", cpuRequest=config.participantCpu, memoryLimitMiB=config.participantMemoryMib, proxyToGrpcWeb="sw-lg-api", extraEnvVars=
               c.appUserNameEnvBinding("splitwell_validator") + [
    { name: "CANTON_PARTICIPANT_POSTGRES_SERVER", value: "sw-postgres" },
    { name: "CANTON_PARTICIPANT_POSTGRES_SCHEMA", value: "splitwell_participant" },
    { name: "CANTON_PARTICIPANT_USERS", json: [
      {
        name: { env: "CN_APP_SPLITWELL_VALIDATOR_LEDGER_API_AUTH_USER_NAME" },
        primaryParty: { allocate: "splitwell_validator_service_user" },
        actAs: [{ fromUser: "self" }],
        readAs: [],
        admin: true,
      },
    ] },
    { name: "CANTON_PARTICIPANT_EXTRA_DOMAINS", json: [
      {
        alias: "splitwell",
        url: "http://splitwell-domain:5008",
      },
    ] },
  ]),

  c.deployment(config, "splitwell-validator-app", [
    {
      name: "sw-val-http",
      port: 6203,
    },
  ], extraEnvVars=c.appAuthEnvBinding("splitwell_validator") + c.appUserNameEnvBindings(["splitwell", "splitwell_wallet"]) + [{ name: "CN_APP_SPLITWELL_PROVIDER_WALLET_USER_NAME", value: "auth0|63e12e0415ad881ffe914e61" }]),


  c.deployment(config, "splitwell-wallet-app", [
    {
      name: "sw-wal-http",
      port: 6004,
    },
  ], image="wallet-app", extraEnvVars=c.appAuthEnvBinding("splitwell_wallet", "wallet") + [
    { name: "CN_APP_WALLET_PARTICIPANT_ADDRESS", value: "splitwell-participant" },
    { name: "CN_APP_WALLET_VALIDATOR_ADDRESS", value: "splitwell-validator-app" },
    { name: "CN_APP_WALLET_VALIDATOR_GRPC_PORT", value: "5203" },
    { name: "CN_APP_WALLET_VALIDATOR_HTTP_PORT", value: "6203" },
  ]),

  c.deployment(config, "splitwell-wallet-web-ui", [
    {
      name: "sw-wal-ui",
      port: 80,
      internalOnly: true,
    },
  ], image="wallet-web-ui", cpuRequest=0.5, extraEnvVars=[
    { name: "CN_APP_WALLET_UI_AUTH_CLIENT_ID", value: "eeMLQ6qljnUcg9o1sJRbt4suCn2CYbSL" },
  ]),

  c.deployment(config, "splitwell-app", [
    {
      name: "sw-api",
      port: 5213,
    },
  ], proxyToGrpcWeb="sw-api", extraEnvVars=c.appAuthEnvBinding("splitwell")),
];

local cantonNetwork(config) =
  c.cluster(config, [
    c.deployment(config, "docs", [
      {
        name: "http",
        port: 80,
      },
      {
        name: "https",
        port: 443,
      },
    ]),
    c.deployment(config, "gcs-proxy", [
      {
        name: "http",
        port: 8080,
        internalOnly: true,
      },
    ], cpuRequest=0.5, memoryLimitMiB=512),

    svcDeployments(config),
    validator1Deployments(config),
    splitwellDeployments(config),
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
