local deployment(imageTag, name, ports, ext={}) = {
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
            image: 'us-central1-docker.pkg.dev/cn-devnet-353712/cn-devnet-images/' + name + ':' + imageTag,
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
    ],
  },
};

function(imageTag, ipAddr) {
  apiVersion: 'apps/v1',
  kind: 'List',
  items: [
    deployment(imageTag, 'docs', [
      {
        containerPort: 80,
        name: 'http',
      },
    ]),
    deployment(
      imageTag, 'canton-domain', [
        {
          name: 'canton-pub-api',
          containerPort: 6018,
        },
        {
          name: 'canton-adm-api',
          containerPort: 6019,
        },
      ], {
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
    deployment(imageTag, 'canton-participant', []),
    externalService('docs', ipAddr, [
      {
        protocol: 'TCP',
        port: 80,
        targetPort: 80,
      },
    ]),
    externalService(
      'canton-domain',
      ipAddr,
      [
        {
          name: 'canton-pub-api',
          protocol: 'TCP',
          port: 6018,
          targetPort: 6018,
        },
        {
          name: 'canton-adm-api',
          protocol: 'TCP',
          port: 6019,
          targetPort: 6019,
        },
      ]
    ),
  ],
}
