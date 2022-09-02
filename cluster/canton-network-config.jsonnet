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

local DOCS_PORTS = [
  {
    name: 'http',
    port: 80,
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

local ALL_PORTS = flatten([
  DOCS_PORTS,
  SVC_APP_PORTS,
  SCAN_APP_PORTS,
  CANTON_DOMAIN_PORTS,
  CANTON_PARTICIPANT_PORTS,
]);

local deployment(config, name, ports, ext={}) = [
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
              image: config.gcpRegion + '-docker.pkg.dev/' + config.gcpRepoName + '/' + name + ':' + config.imageTag,
              imagePullPolicy: 'Always',
              ports: [{ name: p.name, containerPort: p.port } for p in ports],
            } + ext,
          ],
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
      ports: [{ name: p.name, protocol: 'TCP', port: p.port } for p in ports],
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

local cantonNetwork(config) = objects(
  [
    deployment(config, 'docs', DOCS_PORTS),
    deployment(config, 'svc-app', SVC_APP_PORTS),
    deployment(config, 'scan-app', SCAN_APP_PORTS),
    deployment(
      config,
      'canton-domain',
      CANTON_DOMAIN_PORTS,
      {
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
      },
    ),
    deployment(config, 'canton-participant', CANTON_PARTICIPANT_PORTS),
    deployment(config, 'external-proxy', ALL_PORTS),
    externalService(config, ALL_PORTS),
  ],
);

function(gcpRegion, gcpRepoName, imageTag, ipAddr) cantonNetwork(networkDefaults {
  gcpRegion: gcpRegion,
  gcpRepoName: gcpRepoName,
  imageTag: imageTag,
  ipAddr: ipAddr,
})
