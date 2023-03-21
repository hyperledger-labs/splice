local postgres = import "./postgres.jsonnet";

local c = import "./cluster.jsonnet";

local deployments(config) = [
  c.namespace("validator1", config),
  postgres.database("postgres", config, namespace="validator1"),
  c.deployment(config,
               "participant",
               [
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
                 {
                   name: "json-api",
                   port: 7575,
                   externalPort: 7575,
                 },
               ],
               image="canton-participant",
               namespace="validator1",
               cpuRequest=config.participantCpu,
               memoryLimitMiB=config.participantMemoryMib,
               jsonApi=c.jsonApiConfig(config),
               proxyToGrpcWeb=["val1-lg-api", "val1-adm-api"],
               extraEnvVars=c.appUserNameEnvBinding("validator") + [
                 { name: "CANTON_PARTICIPANT_POSTGRES_SERVER", value: "postgres" },
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
                     url: "http://domain.splitwell:5008",
                   },
                 ] },
               ]),

  c.deployment(config,
               "validator-app",
               [
                 {
                   name: "val1-val-http",
                   port: 6003,
                   internalOnly: true,
                 },
               ],
               image="validator-app",
               namespace="validator1",
               extraEnvVars=c.appAuthEnvBinding(config.fixedTokens, "validator") + c.appUserNameEnvBinding("wallet") +
                            [
                              { name: "CN_APP_VALIDATOR_WALLET_USER_NAME", value: "auth0|63e3d75ff4114d87a2c1e4f5" },
                              { name: "CN_APP_DARS", json: ["cn-node-0.1.0-SNAPSHOT/dars/directory-service-0.1.0.dar", "cn-node-0.1.0-SNAPSHOT/dars/splitwell-0.1.0.dar"] },
                              { name: "CN_APP_VALIDATOR_PARTICIPANT_ADDRESS", value: "participant" },
                            ]),

  c.deployment(config, "wallet-app", [
    {
      name: "val1-wal-http",
      port: 6004,
      // Internal, we proxy this under /v0/wallet in the UI.
      internalOnly: true,
    },
  ], image="wallet-app", namespace="validator1", extraEnvVars=c.appAuthEnvBinding(config.fixedTokens, "wallet") + [
    { name: "CN_APP_WALLET_PARTICIPANT_ADDRESS", value: "participant" },
    { name: "CN_APP_WALLET_VALIDATOR_ADDRESS", value: "validator-app" },
    { name: "CN_APP_WALLET_VALIDATOR_GRPC_PORT", value: "5103" },
  ]),

  c.deployment(config, "wallet-web-ui", [
    {
      name: "val1-wal-ui",
      port: 80,
      internalOnly: true,
    },
  ], image="wallet-web-ui", namespace="validator1", cpuRequest=0.5, extraEnvVars=c.appUiAuthEnvBinding("wallet")),

  c.deployment(config, "wallet-new-web-ui", [
    {
      name: "val1-wal-new-ui",
      port: 80,
      internalOnly: true,
    },
  ], image="wallet-new-web-ui", namespace="validator1", cpuRequest=0.5, extraEnvVars=c.appUiAuthEnvBinding("wallet")),

  c.deployment(config, "directory-web-ui", [
    {
      name: "val1-dir-ui",
      port: 80,
      internalOnly: true,
    },
  ], image="validator1-directory-web-ui", namespace="validator1", cpuRequest=0.5, extraEnvVars=c.appUiAuthEnvBinding("directory")),

  c.deployment(config, "splitwell-web-ui", [
    {
      name: "val1-sw-ui",
      port: 80,
      internalOnly: true,
    },
  ], image="validator1-splitwell-web-ui", namespace="validator1", cpuRequest=0.5, extraEnvVars=c.appUiAuthEnvBinding("splitwell")),
];

{
  deployments:: deployments,
}
