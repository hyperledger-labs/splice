local postgres = import "./postgres.jsonnet";

local c = import "./cluster.jsonnet";

local svnode = import "./svnode-config.jsonnet";

local deployments(config) = [
  c.namespace("svc", config),
  postgres.database("postgres", config, namespace="svc"),
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
    namespace="svc",
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
  c.deployment(config, "participant", [
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
  ], image="canton-participant", namespace="svc", cpuRequest=config.participantCpu, memoryLimitMiB=config.participantMemoryMib, extraEnvVars=
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
  ], namespace="svc", extraEnvVars=c.appAuthEnvBinding(config.fixedTokens, "directory")),

  c.deployment(config, "svc-app", [
    {
      name: "svc-app-adm-api",
      port: 5005,
    },
  ], namespace="svc", extraEnvVars=c.appAuthEnvBinding(config.fixedTokens, "svc")),

  [svnode.deployments(num, config) for num in std.range(1, config.numberOfSvNodes)],

  c.deployment(config, "scan-app", [
    {
      name: "scan-api",
      port: 5012,
    },
    {
      name: "scan-api-http",
      port: 6012,
    },
  ], namespace="svc", extraEnvVars=c.appAuthEnvBinding(config.fixedTokens, "scan")),

  c.deployment(config, "scan-web-ui", [
    {
      name: "scan-ui",
      port: 80,
      internalOnly: true,
    },
  ], image="scan-web-ui", namespace="svc", cpuRequest=0.5),
];

{
  deployments:: deployments,
}
