local postgres = import "./postgres.jsonnet";

local c = import "./cluster.jsonnet";

local deployments(config) = [
  c.namespace("splitwell", config),
  postgres.database("postgres", config, namespace="splitwell"),
  c.deployment(
    config,
    "domain",
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
    namespace="splitwell",
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
      { name: "CANTON_DOMAIN_POSTGRES_SERVER", value: "postgres" },
    ]
  ),
  c.deployment(config, "participant", [
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
  ], image="canton-participant", namespace="splitwell", cpuRequest=config.participantCpu, memoryLimitMiB=config.participantMemoryMib, proxyToGrpcWeb=["sw-lg-api", "sw-adm-api"], extraEnvVars=
               c.appUserNameEnvBinding("validator", "splitwell_validator") + [
    { name: "CANTON_PARTICIPANT_POSTGRES_SERVER", value: "postgres" },
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
        url: "http://domain:5008",
      },
    ] },
  ]),

  c.deployment(config, "validator-app", [
    {
      name: "sw-val-http",
      port: 6003,
      internalOnly: true,
    },
  ], image="validator-app", namespace="splitwell", extraEnvVars=c.appAuthEnvBinding(config.fixedTokens, "validator", "validator") +
                                                             c.appUserNameEnvBinding("splitwell") +
                                                             c.appUserNameEnvBinding("wallet") +
                                                             [
                                                               {
                                                                 name: "ADDITIONAL_CONFIG",
                                                                 value: |||
                                                                   canton.validator-apps.validator_backend.app-instances.splitwise = {
                                                                     service-user = ${?CN_APP_SPLITWELL_LEDGER_API_AUTH_USER_NAME}
                                                                     wallet-user = ${?CN_APP_SPLITWELL_PROVIDER_WALLET_USER_NAME}
                                                                     dars = ["coin-0.1.0-SNAPSHOT/dars/splitwell-0.1.0.dar"]
                                                                   }
                                                                 |||,
                                                               },
                                                               { name: "CN_APP_SPLITWELL_PROVIDER_WALLET_USER_NAME", value: "auth0|63e12e0415ad881ffe914e61" }
                                                               { name: "CN_APP_VALIDATOR_PARTICIPANT_ADDRESS", value: "participant" },
                                                             ]),

  c.deployment(config, "wallet-app", [
    {
      name: "sw-wal-http",
      port: 6004,
      // Internal, we proxy this under /v0/wallet in the UI.
      internalOnly: true,
    },
  ], image="wallet-app", namespace="splitwell", extraEnvVars=c.appAuthEnvBinding(config.fixedTokens, "wallet") + [
    { name: "CN_APP_WALLET_PARTICIPANT_ADDRESS", value: "participant" },
    { name: "CN_APP_WALLET_VALIDATOR_ADDRESS", value: "validator-app" },
    { name: "CN_APP_WALLET_VALIDATOR_GRPC_PORT", value: "5203" },
  ]),

  c.deployment(config, "wallet-web-ui", [
    {
      name: "sw-wal-ui",
      port: 80,
      internalOnly: true,
    },
  ], image="wallet-web-ui", namespace="splitwell", cpuRequest=0.5, extraEnvVars=c.appUiAuthEnvBinding("wallet")),

  c.deployment(config, "wallet-new-web-ui", [
    {
      name: "sw-wal-new-ui",
      port: 80,
      internalOnly: true,
    },
  ], image="wallet-new-web-ui", namespace="splitwell", cpuRequest=0.5, extraEnvVars=c.appUiAuthEnvBinding("wallet")),

  c.deployment(config, "splitwell-app", [
    {
      name: "sw-api",
      port: 5213,
    },
  ], namespace="splitwell", proxyToGrpcWeb=["sw-api"], extraEnvVars=c.appAuthEnvBinding(config.fixedTokens, "splitwell")),
];


{
  deployments:: deployments,
}
