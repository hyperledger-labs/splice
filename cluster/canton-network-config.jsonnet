local deployment(gcpRegion, gcpRepoName, imageTag, name, ports, ext={}) = {
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
            image: gcpRegion + '-docker.pkg.dev/' + gcpRepoName + '/' + name + ':' + imageTag,
            imagePullPolicy: 'Always',
            ports: ports,
          } + ext,
        ],
      },
    },
  },
};

local externalService(name, ipAddr, ports) = {
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
    loadBalancerIP: ipAddr,
    loadBalancerSourceRanges: [
      '35.194.81.56/32',
      '35.198.147.95/32',
      '35.189.40.124/32',
      '34.132.91.75/32',
    ],
  },
};

function(gcpRegion, gcpRepoName, imageTag, ipAddr) {
  apiVersion: 'apps/v1',
  kind: 'List',
  items: [
    deployment(gcpRegion, gcpRepoName, imageTag, 'docs', [
      {
        containerPort: 80,
        name: 'http',
      },
    ]),
    deployment(gcpRegion, gcpRepoName, imageTag, 'svc-app', [
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
      gcpRegion,
      gcpRepoName,
      imageTag,
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
    deployment(gcpRegion, gcpRepoName, imageTag, 'canton-participant', [
      {
        name: 'cp-adm-api',
        containerPort: 5002,
      },
      {
        name: 'cp-ledger-api',
        containerPort: 5001,
      },
    ]),
    externalService('docs', ipAddr, [
      {
        protocol: 'TCP',
        port: 80,
        targetPort: 80,
      },
    ]),
    externalService(
      'svc-app',
      ipAddr,
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
      'canton-domain',
      ipAddr,
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
      'canton-participant',
      ipAddr,
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
}
