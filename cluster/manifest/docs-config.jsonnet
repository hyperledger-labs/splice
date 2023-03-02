local c = import "./cluster.jsonnet";

local deployments(config) = [
  c.namespace("docs", config),
  c.deployment(config, "docs", [
    {
      name: "http",
      port: 80,
    },
    {
      name: "https",
      port: 443,
    },
  ], namespace="docs"),
  c.deployment(config, "gcs-proxy", [
    {
      name: "http",
      port: 8080,
      internalOnly: true,
    },
  ], namespace="docs", cpuRequest=0.5, memoryLimitMiB=512),
];

{
  deployments:: deployments,
}
