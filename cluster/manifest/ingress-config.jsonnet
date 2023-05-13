local c = import "./cluster.jsonnet";

local externalService(config, ports) = {
  ports: [],
  deploymentObjects: [
    {
      apiVersion: "v1",
      kind: "Service",
      metadata: {
        name: "external",
        namespace: "cluster-ingress",
        labels: c.standardLabels(config),
      },
      spec: {
        type: "LoadBalancer",
        selector: {
          app: "external-proxy",
        },
        ports: [
          {
            name: p.name,
            protocol: "TCP",
            port: c.externalPort(p),
          }
          for p in ports
        ],
        loadBalancerIP: config.ipAddr,
        loadBalancerSourceRanges: config.externalIPRanges,
      },
    },
  ],
};

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

  // This certificate acquired and populated in Pulumi infrastructure stack.
  local tlsCertSecret = config.clusterName + "-tls";

  [
    clusterVersionConfigMap(config, "cluster-manifest"),
    c.deployment(
      config,
      "external-proxy",
      externalProxyPorts,
      memoryLimitMiB=512,
      mountConfig="cluster-manifest",
      tlsCertSecret=tlsCertSecret,
      namespace="cluster-ingress",
    ),
    externalService(config, externalProxyPorts),
  ];

{
  deployments:: deployments,
}
