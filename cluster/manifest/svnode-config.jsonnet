local postgres = import "./postgres.jsonnet";

local c = import "./cluster.jsonnet";

local deployments(num, svConfig, config) =
  local namespace = std.format("sv-%d", num);
  local adminApi = std.format("sv%d-api", num);
  local svName = std.format("sv%d", num);
  local port = 5014 + 100 * (num - 1);

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
          port: port,
        },
      ],
      image="sv-app",
      extraEnvVars=c.appAuthEnvBinding(config, "sv") + [
        { name: "CN_APP_SV_ADMIN_API_PORT", value: std.toString(port) },
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
            name: "CN_APP_SV_CLIENT_SPONSOR_SV_ADMIN_API_PORT",
            value: "5014",
          },
          {
            name: "CN_APP_SV_CLIENT_SPONSOR_SV_ADMIN_API_ADDRESS",
            value: "http://sv-app.sv-1",
          },
          {
            name: "CN_APP_SV_ONBOARDING_PUBLIC_KEY",
            value: std.get(svConfig, "publicKey"),
          },
          {
            name: "CN_APP_SV_PARTICIPANT_ADDRESS",
            value: "participant",
          },
          // TODO(#4459) Move privateKey to k8s secrets
          {
            name: "ADDITIONAL_CONFIG_SV_ONBOARDING",
            value: |||
              _onboarding {
                type = "join-with-key"
                sv-client.admin-api.port = ${CN_APP_SV_CLIENT_SPONSOR_SV_ADMIN_API_PORT}
                sv-client.admin-api.address = ${CN_APP_SV_CLIENT_SPONSOR_SV_ADMIN_API_ADDRESS}
                public-key = ${CN_APP_SV_ONBOARDING_PUBLIC_KEY}
                private-key = "%s"
              }
            ||| % (std.get(svConfig, "privateKey")),
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
      c.deployment(config, "participant", [
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
      ], image="canton-participant", namespace=namespace, cpuRequest=config.participantCpu, memoryLimitMiB=config.participantMemoryMib, extraEnvVars=
                   c.appUserNameEnvBindings(["sv"]) + c.appUserNameEnvBindings(["validator"]) + [
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
      ]),
    ] else []
  );

{
  deployments:: deployments,
}
