local postgres = import "./postgres.jsonnet";

local c = import "./cluster.jsonnet";

local svnode = import "./svnode-config.jsonnet";

// TODO(#4459) Move these keys to k8s secrets
local svConfig = {
  sv1: {
    walletUser: "auth0|64529b128448ded6aa68048f",
  },
  sv2: {
    publicKey: "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEsRRntNkOLF2Wh7JxV0rBQPgT+SendIjFLXKUXCrLbVHqomkypHQiZP8OgFMSlByOnr81fqiUt3G36LUpg/fmgA==",
    privateKey: "MIGHAgEAMBMGByqGSM49AgEGCCqGSM49AwEHBG0wawIBAQQgOouqxvUir3C9+2apEdOUC40XrbLTdkbBIK78o2m3lOKhRANCAASxFGe02Q4sXZaHsnFXSsFA+BP5J6d0iMUtcpRcKsttUeqiaTKkdCJk/w6AUxKUHI6evzV+qJS3cbfotSmD9+aA",
    walletUser: "auth0|64529b6852dd694167351045",
  },
  sv3: {
    publicKey: "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE0fnbBQiM7UiSNaV6tjPq5lK2buIx5L5nzUuhYWxBk341nFChcbK9pDEO4O6gdxexb/OQP6RhQkDOTDdTCr77CA==",
    privateKey: "MIGHAgEAMBMGByqGSM49AgEGCCqGSM49AwEHBG0wawIBAQQg+8jKTfry5rkitnvy9Dyh5uPVKTzcKu3rrPZyrVW9e/KhRANCAATR+dsFCIztSJI1pXq2M+rmUrZu4jHkvmfNS6FhbEGTfjWcUKFxsr2kMQ7g7qB3F7Fv85A/pGFCQM5MN1MKvvsI",
    walletUser: "auth0|64529bb10c1aee4f2c819218",
  },
  sv4: {
    publicKey: "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEa76d2OWmkpCQ2dTWsWyhofV3tOGdlkhoCnPpY7BbQhCb0s3laR1vp57JYu/d5Cf+332PF2XrgjC0yBWUqM4syQ==",
    privateKey: "MIGHAgEAMBMGByqGSM49AgEGCCqGSM49AwEHBG0wawIBAQQgE5r1MpzeTmvYjtiVLDASw63VA2pfQm4psX7XlUJU8fGhRANCAARrvp3Y5aaSkJDZ1NaxbKGh9Xe04Z2WSGgKc+ljsFtCEJvSzeVpHW+nnsli793kJ/7ffY8XZeuCMLTIFZSozizJ",
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
  ]),

  c.deployment(config, "directory-app", [
    {
      name: "dir-api",
      port: 5010,
    },
  ], namespace="svc", extraEnvVars=c.appAuthEnvBinding(config, "directory")),

  c.deployment(config, "svc-app", [
    {
      name: "svc-app-adm-api",
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
