local networkDefaults = import './network-defaults.jsonnet';

local flatten(obj) =
  if std.isArray(obj)
  then std.flatMap(flatten, obj)
  else [obj];

local objects(items) = {
  apiVersion: 'apps/v1',
  kind: 'List',
  items: flatten(items),
};

local imageName(config, name) =
  config.gcpRegion + '-docker.pkg.dev/' + config.gcpRepoName + '/' + name + ':' + config.imageTag;

// The amount of memory reserved for the operating system in containers
// hosting a JVM. The JVM heap size is the container limit less this
// amount. The number here is a best estimate and may need to be
// adjusted.
local JVM_SYSTEM_MEMORY_MIB = 256;

local DOCS_PORTS = [
  {
    name: 'http',
    port: 80,
  },
];

local GCS_PROXY_PORTS = [
  {
    name: 'http',
    port: 8080,
  },
];

local SVC_APP_PORTS = [
  {
    name: 'svc-app-adm-api',
    port: 5005,
  },
];

local SCAN_APP_PORTS = [
  {
    name: 'scan-api',
    port: 5012,
  },
];

local DIRECTORY_APP_PORTS = [
  {
    name: 'directory-api',
    port: 5010,
  },
];

local DIRECTORY_APP_PORT_PROXIED_TO_GRPC_WEB = {
  name: 'grpc-web',
  grpcPort: 5010,
};

local toGrpcWebPort(port) = {
  name: port.name,
  port: port.grpcPort + 1000,
};

local CANTON_DOMAIN_PORTS = [
  {
    name: 'canton-pub-api',
    port: 5008,
  },
  {
    name: 'canton-adm-api',
    port: 5009,
  },
];


local CANTON_PARTICIPANT_PORTS = [
  {
    name: 'cp-adm-api',
    port: 5002,
  },
  {
    name: 'cp-ledger-api',
    port: 5001,
  },
];


local VALIDATOR1_VALIDATOR_PORTS = [
  {
    name: 'val1-val-api',
    port: 5103,
  },
];

local VALIDATOR1_WALLET_PORTS = [
  {
    name: 'val1-wal-api',
    port: 5104,
  },
];

local VALIDATOR1_WALLET_PORT_PROXIED_TO_GRPC_WEB = {
  name: 'val1-wal-gweb',
  grpcPort: 5104,
};

local VALIDATOR1_WALLET_UI_PORTS_EXTERNAL = [
  {
    name: 'val1-wal-ui',
    port: 7104,
  },
];

local VALIDATOR1_WALLET_UI_PORTS_INTERNAL = [
  {
    name: 'val1-wal-ui',
    port: 80,
  },
];

local VALIDATOR1_DIRECTORY_UI_PORTS_EXTERNAL = [
  {
    name: 'val1-dir-ui',
    port: 7010,
  },
];

local VALIDATOR1_DIRECTORY_UI_PORTS_INTERNAL = [
  {
    name: 'val1-dir-ui',
    port: 80,
  },
];

local VALIDATOR1_PARTICIPANT_PORTS = [
  {
    name: 'val1-adm-api',
    port: 5102,
  },
  {
    name: 'val1-ledger-api',
    port: 5101,
  },
];


local VALIDATOR1_LEDGER_API_PORT_PROXIED_TO_GRPC_WEB = {
  name: 'val1-lapi-gweb',
  grpcPort: 5101,
};


local ALL_PORTS = flatten([
  DOCS_PORTS,
  SVC_APP_PORTS,
  SCAN_APP_PORTS,
  DIRECTORY_APP_PORTS,
  toGrpcWebPort(DIRECTORY_APP_PORT_PROXIED_TO_GRPC_WEB),
  CANTON_DOMAIN_PORTS,
  CANTON_PARTICIPANT_PORTS,
  VALIDATOR1_VALIDATOR_PORTS,
  VALIDATOR1_WALLET_PORTS,
  toGrpcWebPort(VALIDATOR1_WALLET_PORT_PROXIED_TO_GRPC_WEB),
  VALIDATOR1_PARTICIPANT_PORTS,
  VALIDATOR1_WALLET_UI_PORTS_EXTERNAL,
  VALIDATOR1_DIRECTORY_UI_PORTS_EXTERNAL,
  toGrpcWebPort(VALIDATOR1_LEDGER_API_PORT_PROXIED_TO_GRPC_WEB),
]);

local deployment(config, name, ports, memoryLimitMiB=1024, ext={}, proxyToGrpcWeb=null) = [
  {
    apiVersion: 'apps/v1',
    kind: 'Deployment',
    metadata: {
      name: name,
      labels: {
        app: name,
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
            external: 'true',
          },
        },
        spec: {
          containers: [
            {
              name: name,
              image: imageName(config, name),
              imagePullPolicy: 'Always',
              ports: [{ name: p.name, containerPort: p.port } for p in ports],
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
            if proxyToGrpcWeb != null then
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
                  ports: [{ name: 'grpc-web', containerPort: toGrpcWebPort(proxyToGrpcWeb).port }],
                  env: [
                    {
                      name: 'GRPC_ADDRESS',
                      value: '127.0.0.1',
                    },
                    {
                      name: 'GRPC_PORT',
                      value: std.toString(proxyToGrpcWeb.grpcPort),
                    },
                    {
                      name: 'GRPC_WEB_PORT',
                      value: std.toString(toGrpcWebPort(proxyToGrpcWeb).port),
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
    },
    spec: {
      selector: {
        app: name,
      },
      ports: [{ name: p.name, protocol: 'TCP', port: p.port } for p in (ports + (if proxyToGrpcWeb != null then [toGrpcWebPort(proxyToGrpcWeb)] else []))],
    },
  },
];

local externalService(config, ports) = {
  apiVersion: 'v1',
  kind: 'Service',
  metadata: {
    name: 'external',
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
};

// memoryLimitMiB values for deployments are taken emperically from
// DevNet with `kubectl top pod`. Note that these were taken on a very
// lightly loaded cluster and will very likely need to be revised for
// clusters with higher loads.

local cantonNetwork(config) = objects(
  [
    deployment(config, 'docs', DOCS_PORTS),
    deployment(config, 'svc-app', SVC_APP_PORTS),
    deployment(config, 'scan-app', SCAN_APP_PORTS),
    deployment(config, 'directory-app', DIRECTORY_APP_PORTS, proxyToGrpcWeb=DIRECTORY_APP_PORT_PROXIED_TO_GRPC_WEB),
    deployment(
      config,
      'canton-domain',
      CANTON_DOMAIN_PORTS,
      ext={
        readinessProbe: {
          tcpSocket: {
            port: 'canton-pub-api',
          },
        },
        livenessProbe: {
          tcpSocket: {
            port: 'canton-pub-api',
          },
          failureThreshold: 5,
          periodSeconds: 10,
        },
        startupPrope: {
          tcpSocket: {
            port: 'canton-pub-api',
          },
          failureThreshold: 20,
          periodSeconds: 10,
        },
      },

    ),
    deployment(config, 'canton-participant', CANTON_PARTICIPANT_PORTS, memoryLimitMiB=1536),
    deployment(config, 'validator1-participant', VALIDATOR1_PARTICIPANT_PORTS, memoryLimitMiB=1536, proxyToGrpcWeb=VALIDATOR1_LEDGER_API_PORT_PROXIED_TO_GRPC_WEB),
    deployment(config, 'validator1-validator-app', VALIDATOR1_VALIDATOR_PORTS),
    deployment(config, 'validator1-wallet-app', VALIDATOR1_WALLET_PORTS, proxyToGrpcWeb=VALIDATOR1_WALLET_PORT_PROXIED_TO_GRPC_WEB),
    deployment(config, 'validator1-wallet-web-ui', VALIDATOR1_WALLET_UI_PORTS_INTERNAL),
    deployment(config, 'validator1-directory-web-ui', VALIDATOR1_DIRECTORY_UI_PORTS_INTERNAL),
    deployment(config, 'gcs-proxy', GCS_PROXY_PORTS, memoryLimitMiB=512),
    deployment(config, 'external-proxy', ALL_PORTS, memoryLimitMiB=512),
    externalService(config, ALL_PORTS),
  ],
);

function(gcpRegion, gcpRepoName, imageTag, ipAddr) cantonNetwork(networkDefaults {
  gcpRegion: gcpRegion,
  gcpRepoName: gcpRepoName,
  imageTag: imageTag,
  ipAddr: ipAddr,
})
