local postgres = import "./postgres.jsonnet";

local c = import "./cluster.jsonnet";

local svnode = import "./svnode-config.jsonnet";

local svConfig = {
  sv1: {
    walletUser: "auth0|64529b128448ded6aa68048f",
  },
  sv2: {
    walletUser: "auth0|64529b6852dd694167351045",
  },
  sv3: {
    walletUser: "auth0|64529bb10c1aee4f2c819218",
  },
  sv4: {
    walletUser: "auth0|64529bc58d30358eacae5611",
  },
};

local deployments(config) = [
  c.namespace("svc", config),
  postgres.database("postgres", config, namespace="svc"),
  c.deployment(
    config,
    "global-domain",
    [
      {
        name: "grpc-cd-pub-api",
        port: 5008,
      },
      {
        name: "grpc-cd-adm-api",
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
          port: "grpc-cd-pub-api",
        },
      },
      livenessProbe: {
        tcpSocket: {
          port: "grpc-cd-pub-api",
        },
        failureThreshold: 5,
        periodSeconds: 10,
      },
      startupProbe: {
        tcpSocket: {
          port: "grpc-cd-pub-api",
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
    "participant",
    [
      {
        name: "grpc-svcp-adm",
        port: 5002,
      },
      {
        name: "grpc-svcp-lg",
        port: 5001,
      },
      {
        name: "svcp-metrics",
        port: 10013,
        externalPort: 10013,
      },
    ],
    image="canton-participant",
    namespace="svc",
    cpuRequest=config.participantCpu,
    memoryLimitMiB=config.participantMemoryMib,
    livenessProbeConfig={
      grpc: {
        port: 5061,
        service: "liveness",
      },
      initialDelaySeconds: 60,
      periodSeconds: 60,
      failureThreshold: 5,
      timeoutSeconds: 10,
    },
    readinessProbeConfig={
      grpc: {
        port: 5061,
      },
      initialDelaySeconds: 20,
      periodSeconds: 10,
      failureThreshold: 3,
      timeoutSeconds: 10,
    },
    extraEnvVars=
    c.appUserNameEnvBinding("sv1", "sv") + c.appUserNameEnvBinding("sv1-validator", "validator") + c.appUserNameEnvBindings(["svc", "scan", "directory"]) + [
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
          name: { env: "CN_APP_SV_LEDGER_API_AUTH_USER_NAME" },
          primaryParty: { allocate: "Canton-Foundation-1" },
          actAs: [{ fromUser: "self" }, { fromUser: { env: "CN_APP_SVC_LEDGER_API_AUTH_USER_NAME" } }],
          readAs: [],
          admin: true,
        },
        {
          name: { env: "CN_APP_VALIDATOR_LEDGER_API_AUTH_USER_NAME" },
          primaryParty: { fromUser: { env: "CN_APP_SV_LEDGER_API_AUTH_USER_NAME" } },
          actAs: [{ fromUser: "self" }],
          readAs: [],
          admin: true,
        },
      ] },
    ]
  ),

  c.deployment(config, "directory-app", [
    {
      name: "dir-api",
      port: 5010,
    },
  ], namespace="svc", extraEnvVars=c.appAuthEnvBinding(config, "directory")),

  c.deployment(config, "svc-app", [
    {
      name: "grpc-svc-adm",
      port: 5005,
    },
  ], namespace="svc", extraEnvVars=c.appAuthEnvBinding(config, "svc")),

  [svnode.deployments(num, std.get(svConfig, std.format("sv%d", num)), config) for num in std.range(1, config.numberOfSvNodes)],

  c.deployment(config, "scan-app", [
    {
      name: "scan-api",
      port: 5012,
    },
  ], namespace="svc", extraEnvVars=c.appAuthEnvBinding(config, "scan")),

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
