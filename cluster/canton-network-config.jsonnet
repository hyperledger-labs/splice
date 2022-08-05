local networkDefaults = import './network-defaults.jsonnet';

local deployment(config, name, ports, ext={}) = {
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
    selector: {
      matchLabels: {
        app: name,
      },
    },
    template: {
      metadata: {
        labels: {
          app: name,
        },
      },
      spec: {
        containers: [
          {
            name: name,
            image: config.gcpRegion + '-docker.pkg.dev/' + config.gcpRepoName + '/' + name + ':' + config.imageTag,
            imagePullPolicy: 'Always',
            ports: ports,
          } + ext,
        ],
      },
    },
  },
};

local externalService(config, name, ports) = {
  apiVersion: 'v1',
  kind: 'Service',
  metadata: {
    name: name,
  },
  spec: {
    type: 'LoadBalancer',
    selector: {
      app: name,
    },
    ports: ports,
    loadBalancerIP: config.ipAddr,
    loadBalancerSourceRanges: config.externalIPRanges,
  },
};

local cantonNetwork(config) = {
  apiVersion: 'apps/v1',
  kind: 'List',
  items: [
    deployment(config, 'docs', [
      {
        containerPort: 80,
        name: 'http',
      },
    ]),
    deployment(config, 'svc-app', [
      {
        name: 'svc-app-adm-api',
        containerPort: 5005,
      },
      {
        name: 'scan-api',
        containerPort: 5012,
      },
    ]),
    deployment(
      config,
      'canton-domain',
      [
        {
          name: 'canton-pub-api',
          containerPort: 5008,
        },
        {
          name: 'canton-adm-api',
          containerPort: 5009,
        },
      ],
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
    deployment(config, 'canton-participant', [
      {
        name: 'cp-adm-api',
        containerPort: 5002,
      },
      {
        name: 'cp-ledger-api',
        containerPort: 5001,
      },
    ]),
    externalService(config, 'docs', [
      {
        protocol: 'TCP',
        port: 80,
        targetPort: 80,
      },
    ]),
    externalService(
      config,
      'svc-app',
      [
        {
          name: 'svc-app-adm-api',
          protocol: 'TCP',
          port: 5005,
          targetPort: 5005,
        },
        {
          name: 'scan-api',
          protocol: 'TCP',
          port: 5012,
          targetPort: 5012,
        },
      ]
    ),
    externalService(
      config,
      'canton-domain',
      [
        {
          name: 'canton-pub-api',
          protocol: 'TCP',
          port: 5008,
          targetPort: 5008,
        },
        {
          name: 'canton-adm-api',
          protocol: 'TCP',
          port: 5009,
          targetPort: 5009,
        },
      ]
    ),
    externalService(
      config,
      'canton-participant',
      [
        {
          name: 'cp-adm-api',
          protocol: 'TCP',
          port: 5002,
          targetPort: 5002,
        },
        {
          name: 'cp-ledger-api',
          protocol: 'TCP',
          port: 5001,
          targetPort: 5001,
        },
      ]
    ),
  ],
};

function(gcpRegion, gcpRepoName, imageTag, ipAddr) cantonNetwork(networkDefaults {
  gcpRegion: gcpRegion,
  gcpRepoName: gcpRepoName,
  imageTag: imageTag,
  ipAddr: ipAddr,
})
