local postgres = import "./postgres.jsonnet";

local c = import "./cluster.jsonnet";

local validatorConfigOverrides = import "../overrides/validator-app.json";

local deployments(config) = [
  c.namespace("validator1", config),
  postgres.database("postgres", config, namespace="validator1"),
  c.deployment(config,
               "participant",
               [
                 {
                   name: "grpc-val1-adm",
                   port: 5002,
                   externalPort: 5102,
                 },
                 {
                   name: "grpc-val1-lg",
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
               proxyToGrpcWeb=["grpc-val1-lg"],
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

  c.deployment(config + validatorConfigOverrides,
               "validator-app",
               [
                 {
                   name: "val1-val",
                   port: 5003,
                   internalOnly: true,
                 },
               ],
               image="validator-app",
               namespace="validator1",
               extraEnvVars=c.appAuthEnvBinding(config, "validator") +
                            [
                              { name: "CN_APP_VALIDATOR_WALLET_USER_NAME", value: "auth0|63e3d75ff4114d87a2c1e4f5" },
                              { name: "CN_APP_DARS", json: ["cn-node-0.1.0-SNAPSHOT/dars/directory-service-0.1.0.dar", "cn-node-0.1.0-SNAPSHOT/dars/splitwell-0.1.0.dar"] },
                              { name: "CN_APP_VALIDATOR_PARTICIPANT_ADDRESS", value: "participant" },
                            ],
               memoryLimitMiB=4096),

  c.deployment(config, "wallet-web-ui", [
    {
      name: "val1-wal-ui",
      port: 80,
      internalOnly: true,
    },
  ], image="wallet-web-ui", namespace="validator1", cpuRequest=0.5, extraEnvVars=[{ name: "CN_APP_WALLET_UI_CLUSTER", value: config.clusterDnsName }] + c.appUiAuthEnvBinding("wallet")),

  c.deployment(config, "directory-web-ui", [
    {
      name: "val1-dir-ui",
      port: 80,
      internalOnly: true,
    },
  ], image="directory-web-ui", namespace="validator1", cpuRequest=0.5, extraEnvVars=c.appUiAuthEnvBinding("directory")),

  c.deployment(config, "splitwell-web-ui", [
    {
      name: "val1-sw-ui",
      port: 80,
      internalOnly: true,
    },
  ], image="splitwell-web-ui", namespace="validator1", cpuRequest=0.5, extraEnvVars=c.appUiAuthEnvBinding("splitwell")),
];

{
  deployments:: deployments,
}
