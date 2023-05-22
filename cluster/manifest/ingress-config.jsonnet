local c = import "./cluster.jsonnet";

local clusterVersionConfigMap(config, name) = {
  deploymentObjects: [
    {
      apiVersion: "v1",
      kind: "ConfigMap",
      metadata: {
        name: name,
        namespace: "cluster-ingress",
      },
      data: {
        version: config.imageTag,
      },
    },
  ],
};

local deployments(config, deployments) =
  local allPorts = c.flatten(std.map(function(i) i.ports, c.flatten(deployments)));
  local nonInternalPorts = std.filter(function(port) !std.get(port, "internalOnly", false),
                                      allPorts);
  local externalProxyPorts = std.map(function(p) { name: p.name, port: c.externalPort(p) }, nonInternalPorts);

  [
    clusterVersionConfigMap(config, "cluster-manifest"),
    c.deployment(
      config,
      "external-proxy-full",
      externalProxyPorts,
      memoryLimitMiB=512,
      mountConfig="cluster-manifest",
      namespace="cluster-ingress",
    ),
  ];

{
  deployments:: deployments,
}
