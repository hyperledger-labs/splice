local flatten(obj) =
  if std.isArray(obj)
  then std.flatMap(flatten, obj)
  else [obj];

local objects(items) = {
  apiVersion: 'apps/v1',
  kind: 'List',
  items: flatten(std.map(function(i) i.deploymentObjects, items)),
};

local findPort(ports, portName) =
  local matches = std.filter(function(p) p.name == portName, ports);

  if std.length(matches) == 0 then
    error 'Cannot find port: ' + portName
  else if std.length(matches) > 1 then
    error 'Too many ports with name: ' + portName
  else
    matches[0];

local imageName(config, name) =
  config.gcpRegion + '-docker.pkg.dev/' + config.gcpRepoName + '/' + name + ':' + config.imageTag;

local validPortName(name) =
  if std.length(name) <= 15 then
    name
  else
    error 'port name too long: ' + name;

local toGrpcWebPort(port) = {
  name: validPortName(port.name + '-gw'),
  port: port.port + 1000,
};

local toContainerPortDefn(p) = {
  name: validPortName(p.name),
  containerPort: p.port,
};

// The amount of memory reserved for the operating system in containers
// hosting a JVM. The JVM heap size is the container limit less this
// amount. The number here is a best estimate and may need to be
// adjusted.
local JVM_SYSTEM_MEMORY_MIB = 256;


local deployment(config, name, ports, memoryLimitMiB=1024, ext={}, proxyToGrpcWeb=null) =
  local proxyPort =
    if proxyToGrpcWeb == null then null
    else findPort(ports, proxyToGrpcWeb);

  local allPorts = ports + (
    if proxyPort == null then [] else [
      toGrpcWebPort(proxyPort),
    ]
  );

  {
    ports: allPorts,
    deploymentObjects: [
      {
        apiVersion: 'apps/v1',
        kind: 'Deployment',
        metadata: {
          name: name,
          labels: {
            app: name,
            clusterName: config.clusterName,
          },
        },
        spec: {
          replicas: 1,
          strategy: {
            type: 'Recreate',
          },
          selector: {
            matchLabels: {
              app: name,
            },
          },
          template: {
            metadata: {
              labels: {
                app: name,
                clusterName: config.clusterName,
              },
            },
            spec: {
              containers: [
                {
                  name: name,
                  image: imageName(config, name),
                  imagePullPolicy: 'Always',
                  ports: [toContainerPortDefn(p) for p in ports],
                  env: [
                    {
                      name: 'JAVA_TOOL_OPTIONS',
                      value: '-Xms%sM -Xmx%sM' % [memoryLimitMiB - JVM_SYSTEM_MEMORY_MIB, memoryLimitMiB - JVM_SYSTEM_MEMORY_MIB],
                    },
                  ],
                  resources: {
                    requests: {
                      memory: memoryLimitMiB + 'Mi',
                    },
                    limits: {
                      memory: memoryLimitMiB + 'Mi',
                    },
                  },
                } + ext,
              ] + (
                if proxyPort != null then
                  [
                    {
                      name: 'envoy-proxy',
                      image: imageName(config, 'envoy-proxy'),
                      imagePullPolicy: 'Always',
                      resources: {
                        requests: {
                          memory: '256Mi',
                        },
                        limits: {
                          memory: '256Mi',
                        },
                      },
                      ports: [toContainerPortDefn(toGrpcWebPort(proxyPort))],
                      env: [
                        {
                          name: 'GRPC_ADDRESS',
                          value: '127.0.0.1',
                        },
                        {
                          name: 'GRPC_PORT',
                          value: std.toString(proxyPort.port),
                        },
                        {
                          name: 'GRPC_WEB_PORT',
                          value: std.toString(toGrpcWebPort(proxyPort).port),
                        },
                      ],
                    },
                  ] else []
              ),
            },
          },
        },
      },
      {
        apiVersion: 'v1',
        kind: 'Service',
        metadata: {
          name: name,
          clusterName: config.clusterName,
        },
        spec: {
          selector: {
            app: name,
          },
          ports: [{ name: p.name, protocol: 'TCP', port: p.port } for p in allPorts],
        },
      },
    ],
  };

local externalService(config, ports) = {
  ports: [],
  deploymentObjects: [
    {
      apiVersion: 'v1',
      kind: 'Service',
      metadata: {
        name: 'external',
        clusterName: config.clusterName,
      },
      spec: {
        type: 'LoadBalancer',
        selector: {
          app: 'external-proxy',
        },
        ports: [{ name: p.name, protocol: 'TCP', port: p.port } for p in ports],
        loadBalancerIP: config.ipAddr,
        loadBalancerSourceRanges: config.externalIPRanges,
      },
    },
  ],
};

local cluster(config, clusterDeployments) =
  local deployments = flatten(clusterDeployments);

  local allPorts =
    std.filter(function(port) !std.get(port, 'internalOnly', false),
               flatten(std.map(function(i) i.ports, deployments)));

  objects(deployments + [
    deployment(config, 'external-proxy', allPorts, memoryLimitMiB=512),
    externalService(config, allPorts),
  ]);

{
  deployment:: deployment,
  cluster:: cluster,
}
