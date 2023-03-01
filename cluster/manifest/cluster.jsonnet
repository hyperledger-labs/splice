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

local appUserNameEnvBinding(appName, varBaseName=appName) =
  local name = "CN_APP_" + std.asciiUpper(varBaseName) + "_LEDGER_API_AUTH";
  local secret = std.asciiLower(std.strReplace("CN_APP_" + appName + "_LEDGER_API_AUTH", "_", "-"));
  [
    {
      name: name + "_USER_NAME",
      valueFrom: {
        secretKeyRef: {
          name: secret,
          key: "ledger-api-user",
          optional: false,
        },
      },
    },
  ];

local appUserNameEnvBindings(appNames) = std.flatMap(appUserNameEnvBinding, appNames);

local appAuthEnvBinding(fixedTokens, appName, varBaseName=appName) =
  local name = "CN_APP_" + std.asciiUpper(varBaseName) + "_LEDGER_API_AUTH";
  local secret = std.asciiLower(std.strReplace("CN_APP_" + appName + "_LEDGER_API_AUTH", "_", "-"));
  // In staging (where fixedTokens=true by default)
  // we use fixed tokens read from a k8s secret rather than refreshing through client credentials.
  // See https://github.com/DACH-NY/the-real-canton-coin/issues/3053 for more details.
  // We cannot override an object using a substitution so instead we set this through ADDITIONAL_CONFIG
  // which first resets it back to null to disable object merging and then switches to static token config.
  if (fixedTokens) then
    [
      {
        name: "ADDITIONAL_CONFIG",
        value: |||
          _client_credentials_auth_config = null
          _client_credentials_auth_config = {
            type = "static"
            token = ${%s}
          }
        ||| % (name + "_TOKEN"),
      },
      {
        name: name + "_TOKEN",
        valueFrom: {
          secretKeyRef: {
            name: secret,
            key: "token",
            optional: false,
          },
        },
      },
    ] + appUserNameEnvBinding(appName, varBaseName)
  else
    [
      {
        name: name + "_URL",
        valueFrom: {
          secretKeyRef: {
            name: secret,
            key: "url",
            optional: false,
          },
        },
      },
      {
        name: name + "_CLIENT_ID",
        valueFrom: {
          secretKeyRef: {
            name: secret,
            key: "client-id",
            optional: false,
          },
        },
      },
      {
        name: name + "_CLIENT_SECRET",
        valueFrom: {
          secretKeyRef: {
            name: secret,
            key: "client-secret",
            optional: false,
          },
        },
      },
    ] + appUserNameEnvBinding(appName, varBaseName);


local expandEnvironment(env) =
  local additional_config =
    std.join(
      "\n",
      std.filterMap(
        function(binding) binding.name == "ADDITIONAL_CONFIG",
        function(binding) binding.value,
        env
      )
    );
  std.map(function(binding) (
    local json = std.get(binding, "json");
    if json == null then binding else { name: binding.name, value: std.toString(json) }
  ), std.filter(function(binding) binding.name != "ADDITIONAL_CONFIG", env)) +
  (if additional_config == "" then [] else [
     {
       name: "ADDITIONAL_CONFIG",
       value: additional_config,
     },
   ]);

// `image` defaults to `name`
local deployment(config, name, ports, cpuRequest=1, memoryLimitMiB=1536, ext={}, proxyToGrpcWeb=null, mountConfig=null, tlsCertSecret=null, extraEnvVars=[], image=null, namespace=null) =

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
            moduleName: if image == null then name else image,
            clusterName: config.clusterName,
          },
          [if namespace != null then "namespace"]: namespace,
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
                moduleName: if image == null then name else image,
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
                      value: "-Xms%sM -Xmx%sM -Dscala.concurrent.context.minThreads=4" % [memoryLimitMiB * config.jvmHeapMemoryFactor, memoryLimitMiB * config.jvmHeapMemoryFactor],
                    },
                  ] + expandEnvironment(extraEnvVars),
                  resources: {
                    requests: {
                      memory: memoryLimitMiB + "Mi",
                      cpu: cpuRequest,
                    },
                    limits: {
                      memory: memoryLimitMiB + "Mi",
                    },
                  },
                  volumeMounts: (if mountConfig == null then [] else [
                                   {
                                     mountPath: "/config",
                                     name: name + "-config-vol",
                                   },
                                 ]) + (if tlsCertSecret == null then [] else [
                                         {
                                           mountPath: "/tmp",
                                           name: name + "-tls-cert-vol",
                                         },
                                       ]),
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
          [if namespace != null then "namespace"]: namespace,
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
    deployment(
      config,
      "external-proxy",
      externalProxyPorts,
      memoryLimitMiB=512,
      tlsCertSecret=tlsCertSecret
    ),
    externalService(config, externalProxyPorts),
    tls.issuer(issuerName, issuerServer, config.gcpDnsProject, config.gcpDnsSASecret),
    tls.certificate(issuerName, tlsCertSecret, config.clusterName, config.clusterDnsName),
  ]);

local namespace(name, config) = {
  ports: [],
  deploymentObjects: [
    {
      apiVersion: "v1",
      kind: "Namespace",
      metadata: {
        name: name,
        labels: {
          clusterName: config.clusterName,
        },
      },
    },
  ],
};

{
  deployment:: deployment,
  cluster:: cluster,
  appAuthEnvBinding:: appAuthEnvBinding,
  appUserNameEnvBinding:: appUserNameEnvBinding,
  appUserNameEnvBindings:: appUserNameEnvBindings,
  namespace:: namespace,
}
