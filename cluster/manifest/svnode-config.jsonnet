local postgres = import "./postgres.jsonnet";

local c = import "./cluster.jsonnet";

local deployments(num, svConfig, config) =
  local namespace = std.format("sv-%d", num);
  local adminApi = std.format("sv%d-api", num);
  local svName = std.format("Canton-Foundation-%d", num);

  local participantAdminApi = std.format("sv%d-adm-api", num);
  local participantLedgerApi = std.format("sv%d-lg-api", num);
  local participantMetricsApi = std.format("sv%d-metrics", num);
  local participantPostgresSchema = std.format("sv%d_participant", num);

  [
    c.namespace(namespace, config),
    c.deployment(
      config,
      "sv-app",
      [
        {
          name: adminApi,
          port: 5014,
          internalOnly: true,
        },
      ],
      image="sv-app",
      extraEnvVars=c.appAuthEnvBinding(config, "sv") + [
        { name: "CN_APP_SV_IS_DEV_NET", value: "true" },
        { name: "CN_APP_SV_ONBOARDING_NAME", value: svName },
      ] + (
        if num == 1 then [
          {
            // the first one is the founding SV app
            name: "CN_APP_SV_ONBOARDING_TYPE",
            value: "found-collective",
          },
          {
            name: "CN_APP_SV_PARTICIPANT_ADDRESS",
            value: "participant.svc",
          },
        ]
        else [
          {
            name: "CN_APP_SV_ONBOARDING_TYPE",
            value: "join-with-key",
          },
          {
            name: "CN_APP_SV_CLIENT_SPONSOR_SV_ADMIN_API_ADDRESS",
            value: "http://sv-app.sv-1:5014",
          },
          {
            name: "CN_APP_SV_ONBOARDING_PUBLIC_KEY",
            value: std.get(svConfig, "publicKey"),
          },
          {
            name: "CN_APP_SV_PARTICIPANT_ADDRESS",
            value: "participant",
          },
          {
            name: "CN_APP_SV_PUBLIC_KEY",
            valueFrom: {
              secretKeyRef: {
                name: "cn-app-sv-key",
                key: "public",
                optional: false,
              },
            },
          },
          {
            name: "CN_APP_SV_PRIVATE_KEY",
            valueFrom: {
              secretKeyRef: {
                name: "cn-app-sv-key",
                key: "private",
                optional: false,
              },
            },
          },
          {
            name: "ADDITIONAL_CONFIG_SV_ONBOARDING",
            value: |||
              _onboarding {
                type = "join-with-key"
                sv-client.admin-api.url = ${CN_APP_SV_CLIENT_SPONSOR_SV_ADMIN_API_ADDRESS}
              }
            |||,
          },
        ]
      ) + (
        if (config.tickDuration != null && num == 1) then [{ name: "CN_APP_SV_INITIAL_TICK_DURATION", value: config.tickDuration }] else []
      ),
      namespace=namespace
    ),
    c.deployment(
      config,
      "validator-app",
      [
        {
          name: adminApi,
          port: 5003,
          internalOnly: true,
        },
      ],
      image="validator-app",
      extraEnvVars=c.appAuthEnvBinding(config, "validator") + [
        {
          name: "CN_APP_VALIDATOR_PARTICIPANT_ADDRESS",
          value: if (num == 1) then "participant.svc" else "participant",
        },
        {
          name: "CN_APP_VALIDATOR_WALLET_USER_NAME",
          value: std.get(svConfig, "walletUser"),
        },
      ],
      namespace=namespace,
      memoryLimitMiB=4096,
    ),
    c.deployment(config, "sv-web-ui", [
      {
        name: "val1-sv-ui",
        port: 80,
        internalOnly: true,
      },
    ], image="sv-web-ui", namespace=namespace, cpuRequest=0.5, extraEnvVars=[{ name: "CN_APP_SV_UI_CLUSTER", value: config.clusterDnsName }] + c.appUiAuthEnvBinding("sv")),
    c.deployment(config, "wallet-web-ui", [
      {
        name: "val1-wal-ui",
        port: 80,
        internalOnly: true,
      },
    ], image="wallet-web-ui", namespace=namespace, cpuRequest=0.5, extraEnvVars=[{ name: "CN_APP_WALLET_UI_CLUSTER", value: config.clusterDnsName }] + c.appUiAuthEnvBinding("wallet")),
  ] + (
    // No participant is created for sv1 app as it uses the svc participant in svc namespace
    if num != 1 then [
      postgres.database("postgres", config, namespace=namespace),
      c.deployment(
        config,
        "participant",
        [
          {
            name: participantAdminApi,
            port: 5002,
            internalOnly: true,
          },
          {
            name: participantLedgerApi,
            port: 5001,
            internalOnly: true,
          },
          {
            name: participantMetricsApi,
            port: 10013,
            internalOnly: true,
          },
        ],
        image="canton-participant",
        namespace=namespace,
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
        c.appUserNameEnvBindings(["sv", "validator"]) +
        [
          { name: "CANTON_PARTICIPANT_POSTGRES_SERVER", value: "postgres" },
          { name: "CANTON_PARTICIPANT_POSTGRES_SCHEMA", value: participantPostgresSchema },
          { name: "CANTON_PARTICIPANT_USERS", json: [
            {
              name: { env: "CN_APP_SV_LEDGER_API_AUTH_USER_NAME" },
              primaryParty: { allocate: svName },
              actAs: [{ fromUser: "self" }],
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
    ] else []
  ) + (
    if num == 2 then [
      c.deployment(config, "scan-app", [
        {
          name: "scan-api",
          port: 5012,
          internalOnly: true,
        },
      ], namespace=namespace, extraEnvVars=c.appAuthEnvBinding(config, "sv", "scan")),

      c.deployment(config, "scan-web-ui", [
        {
          name: "scan-ui",
          port: 80,
          internalOnly: true,
        },
      ], image="scan-web-ui", namespace=namespace, cpuRequest=0.5),

    ] else []
  );

{
  deployments:: deployments,
}
