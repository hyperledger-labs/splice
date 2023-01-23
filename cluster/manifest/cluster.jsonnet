local tls = import "./tls.jsonnet";

local flatten(obj) =
  if std.isArray(obj)
  then std.flatMap(flatten, obj)
  else [obj];

local objects(items) = {
  apiVersion: "apps/v1",
  kind: "List",
  items: flatten(std.map(function(i) i.deploymentObjects, items)),
};

local findPort(ports, portName) =
  local matches = std.filter(function(p) p.name == portName, ports);

  if std.length(matches) == 0 then
    error "Cannot find port: " + portName
  else if std.length(matches) > 1 then
    error "Too many ports with name: " + portName
  else
    matches[0];

local imageName(config, name) =
  config.gcpRegion + "-docker.pkg.dev/" + config.gcpRepoName + "/" + name + ":" + config.imageTag;

local validPortName(name) =
  if std.length(name) <= 15 then
    name
  else
    error "port name too long: " + name;

local externalPort(port) =
  if std.objectHas(port, "externalPort") then port.externalPort else port.port;

local toGrpcWebPort(port) = {
  name: validPortName(port.name + "-gw"),
  port: port.port + 1000,
  externalPort: externalPort(port) + 1000,
  proxyToGrpc: port.port,
};

local toContainerPortDefn(p) = {
  name: validPortName(p.name),
  containerPort: p.port,
};

local authEnvVars(s) = {
  [s.env + "_URL"]: {
    name: s.env + "_URL",
    valueFrom: {
      secretKeyRef: {
        name: s.secret,
        key: "url",
        optional: false,
      },
    },
  },
  [s.env + "_CLIENT_ID"]: {
    name: s.env + "_CLIENT_ID",
    valueFrom: {
      secretKeyRef: {
        name: s.secret,
        key: "client-id",
        optional: false,
      },
    },
  },
  [s.env + "_CLIENT_SECRET"]: {
    name: s.env + "_CLIENT_SECRET",
    valueFrom: {
      secretKeyRef: {
        name: s.secret,
        key: "client-secret",
        optional: false,
      },
    },
  },
  [s.env + "_USER_NAME"]: {
    name: s.env + "_USER_NAME",
    valueFrom: {
      secretKeyRef: {
        name: s.secret,
        key: "ledger-api-user",
        optional: false,
      },
    },
  },
};

// The amount of memory reserved for the operating system in containers
// hosting a JVM. The JVM heap size is the container limit less this
// amount. The number here is a best estimate and may need to be
// adjusted.
local JVM_SYSTEM_MEMORY_MIB = 512;

// `image` defaults to `name`
local deployment(config, name, ports, memoryLimitMiB=1536, ext={}, proxyToGrpcWeb=null, mountConfig=null, tlsCertSecret=null, extraEnvVars=[], image=null) =

  local proxyPort =
    if proxyToGrpcWeb == null then null
    else findPort(ports, proxyToGrpcWeb);

  local allPorts = ports + (
    if proxyPort == null then [] else [
      toGrpcWebPort(proxyPort),
    ]
  );

  {
    ports: std.map(function(p) (p { service: name }), allPorts),
    deploymentObjects: [
      {
        apiVersion: "apps/v1",
        kind: "Deployment",
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
            type: "Recreate",
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
                  image: imageName(config, if image == null then name else image),
                  imagePullPolicy: "Always",
                  ports: [toContainerPortDefn(p) for p in ports],
                  env: [
                    {
                      name: "JAVA_TOOL_OPTIONS",
                      value: "-Xms%sM -Xmx%sM" % [memoryLimitMiB - JVM_SYSTEM_MEMORY_MIB, memoryLimitMiB - JVM_SYSTEM_MEMORY_MIB],
                    },
                  ] + extraEnvVars,
                  resources: {
                    requests: {
                      memory: memoryLimitMiB + "Mi",
                    },
                    limits: {
                      memory: memoryLimitMiB + "Mi",
                    },
                  },
                  volumeMounts: if mountConfig == null then [] else [
                    {
                      mountPath: "/config",
                      name: name + "-config-vol",
                    },
                  ] + if tlsCertSecret == null then [] else [
                    {
                      mountPath: "/tmp",
                      name: name + "-tls-cert-vol",
                    },
                  ],
                } + ext,
              ] + (
                if proxyPort != null then
                  [
                    {
                      name: "envoy-proxy",
                      image: imageName(config, "envoy-proxy"),
                      imagePullPolicy: "Always",
                      resources: {
                        requests: {
                          memory: "256Mi",
                        },
                        limits: {
                          memory: "256Mi",
                        },
                      },
                      ports: [toContainerPortDefn(toGrpcWebPort(proxyPort))],
                      env: [
                        {
                          name: "GRPC_ADDRESS",
                          value: "127.0.0.1",
                        },
                        {
                          name: "GRPC_PORT",
                          value: std.toString(proxyPort.port),
                        },
                        {
                          name: "GRPC_WEB_PORT",
                          value: std.toString(toGrpcWebPort(proxyPort).port),
                        },
                      ],
                    },
                  ] else []
              ),
              volumes: if mountConfig == null then [] else [
                {
                  name: name + "-config-vol",
                  configMap: {
                    name: mountConfig,
                  },
                },
              ] + if tlsCertSecret == null then [] else [
                {
                  name: name + "-tls-cert-vol",
                  secret: {
                    secretName: tlsCertSecret,
                    optional: false,
                  },
                },
              ],
            },
          },
        },
      },
      {
        apiVersion: "v1",
        kind: "Service",
        metadata: {
          name: name,
          clusterName: config.clusterName,
        },
        spec: {
          selector: {
            app: name,
          },
          ports: [
            {
              name: p.name,
              protocol: "TCP",
              port: p.port,
            }
            for p in allPorts
          ],
        },
      },
    ],
  };

local jsonFileConfigMap(config, name, fileName, data) = {
  ports: [],
  deploymentObjects: [
    {
      apiVersion: "v1",
      kind: "ConfigMap",
      metadata: {
        name: name,
      },
      data: {
        version: config.imageTag,
        [fileName]: std.manifestJsonEx(data, "  ", "\n", ": "),
      },
    },
  ],
};

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
            port: externalPort(p),
          }
          for p in ports
        ],
        loadBalancerIP: config.ipAddr,
        loadBalancerSourceRanges: config.externalIPRanges,
      },
    },
  ],
};


local cluster(config, clusterDeployments) =
  local deployments = flatten(clusterDeployments);

  local tlsCertSecret = config.clusterName + "-tls";
  local issuerName = "letsencrypt-production";
  local issuerServer = "https://acme-v02.api.letsencrypt.org/directory";

  local allPorts = flatten(std.map(function(i) i.ports, deployments));
  local nonInternalPorts = std.filter(function(port) !std.get(port, "internalOnly", false),
                                      allPorts);
  local externalProxyPorts = std.map(function(p) { name: p.name, port: externalPort(p) }, nonInternalPorts);

  objects(deployments + [
    jsonFileConfigMap(config, "cluster-manifest", "manifest.json", {
      ports: allPorts,
    }),
    deployment(
      config,
      "external-proxy",
      externalProxyPorts,
      memoryLimitMiB=512,
      mountConfig="cluster-manifest",
      tlsCertSecret=tlsCertSecret
    ),
    externalService(config, externalProxyPorts),
    tls.issuer(issuerName, issuerServer, config.gcpDnsProject, config.gcpDnsSASecret),
    tls.certificate(issuerName, tlsCertSecret, config.clusterName, config.clusterDnsName),
  ]);

{
  deployment:: deployment,
  cluster:: cluster,
  authEnvVars:: authEnvVars,
}
