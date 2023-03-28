local postgres = import "./postgres.jsonnet";

local c = import "./cluster.jsonnet";

local deployments(num, config) =
  local namespace = std.format("sv-%d", num);
  local adminApi = std.format("sv%d-api", num);
  local authBinding = std.format("sv%d", num);
  local port = 5014 + 100 * (num - 1);

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
      extraEnvVars=c.appAuthEnvBinding(config.fixedTokens, "sv") + [
        { name: "CN_APP_SV_ADMIN_API_PORT", value: std.toString(port) },
        { name: "CN_APP_SV_IS_DEV_NET", value: "true" },
        {
          // the first one is the founding SV app
          name: "CN_APP_SV_BOOTSTRAP_TYPE",
          value: if num == 1 then "found-collective" else "join-via-svc-app",
        },
      ] + (
        if (config.tickDuration != null && num == 1) then [{ name: "CN_APP_SV_INITIAL_TICK_DURATION", value: config.tickDuration }] else []
      ),
      namespace=namespace
    ),
  ];

{
  deployments:: deployments,
}
