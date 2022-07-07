function(imageTag) {
  apiVersion: 'apps/v1',
  kind: 'List',
  items: [
    {
      apiVersion: 'apps/v1',
      kind: 'Deployment',
      metadata: {
        name: 'docs',
        labels: {
          app: 'docs',
        },
      },
      spec: {
        replicas: 1,
        selector: {
          matchLabels: {
            app: 'docs',
          },
        },
        template: {
          metadata: {
            labels: {
              app: 'docs',
            },
          },
          spec: {
            containers: [
              {
                name: 'docs',
                image: 'us-central1-docker.pkg.dev/cn-devnet-353712/cn-devnet-images/docs:' + imageTag,
                ports: [
                  {
                    containerPort: 80,
                  },
                ],
              },
            ],
          },
        },
      },
    },
    {
      apiVersion: 'apps/v1',
      kind: 'Deployment',
      metadata: {
        name: 'canton-domain',
        labels: {
          app: 'canton-domain',
        },
      },
      spec: {
        replicas: 1,
        selector: {
          matchLabels: {
            app: 'canton-domain',
          },
        },
        template: {
          metadata: {
            labels: {
              app: 'canton-domain',
            },
          },
          spec: {
            containers: [
              {
                name: 'canton-domain',
                image: 'us-central1-docker.pkg.dev/cn-devnet-353712/cn-devnet-images/canton-domain:' + imageTag,
                ports: [
                  {
                    name: 'canton-pub-api',
                    containerPort: 6018,
                  },
                  {
                    name: 'canton-adm-api',
                    containerPort: 6019,
                  },
                ],
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
            ],
          },
        },
      },
    },
    {
      apiVersion: 'v1',
      kind: 'Service',
      metadata: {
        name: 'docs',
      },
      spec: {
        type: 'LoadBalancer',
        selector: {
          app: 'docs',
        },
        ports: [
          {
            protocol: 'TCP',
            port: 80,
            targetPort: 80,
          },
        ],
        loadBalancerIP: '35.202.15.15',
        loadBalancerSourceRanges: [
          '35.194.81.56/32',
          '35.198.147.95/32',
          '35.189.40.124/32',
        ],
      },
    },
    {
      apiVersion: 'v1',
      kind: 'Service',
      metadata: {
        name: 'canton-domain',
      },
      spec: {
        type: 'LoadBalancer',
        selector: {
          app: 'canton-domain',
        },
        ports: [
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
        ],
        loadBalancerIP: '35.202.15.15',
        loadBalancerSourceRanges: [
          '35.194.81.56/32',
          '35.198.147.95/32',
          '35.189.40.124/32',
        ],
      },
    },
  ],
}
