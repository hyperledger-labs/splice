local c = import "./cluster.jsonnet";

local tls = import "./tls.jsonnet";

local externalService(config, ports) = {
  ports: [],
  deploymentObjects: [
    {
      apiVersion: "v1",
      kind: "Service",
      metadata: {
        name: "external",
        clusterName: config.clusterName,
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

local deployments(config, deployments) =
  local allPorts = c.flatten(std.map(function(i) i.ports, c.flatten(deployments)));
  local nonInternalPorts = std.filter(function(port) !std.get(port, "internalOnly", false),
                                      allPorts);
  local externalProxyPorts = std.map(function(p) { name: p.name, port: c.externalPort(p) }, nonInternalPorts);

  local tlsCertSecret = config.clusterName + "-tls";

  [
    c.jsonFileConfigMap(config, "cluster-manifest"),
    c.deployment(
      config,
      "external-proxy",
      externalProxyPorts,
      memoryLimitMiB=512,
      mountConfig="cluster-manifest",
      tlsCertSecret=tlsCertSecret
    ),
    externalService(config, externalProxyPorts),
    tls.issuer(config.tls.issuerName, config.tls.issuerServer, config.gcpDnsProject, config.gcpDnsSASecret),
    tls.certificate(config.tls.issuerName, tlsCertSecret, config.clusterName, config.clusterDnsName),
  ];

{
  deployments:: deployments,
}
