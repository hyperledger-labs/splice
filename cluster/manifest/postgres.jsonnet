local database(name, config) = {
  ports: [],
  deploymentObjects: [
    {
      apiVersion: 'v1',
      kind: 'ConfigMap',
      metadata: {
        name: name + '-configuration',
        labels: {
          app: name,
        },
      },
      data: {
        PGDATA: '/var/lib/postgresql/data/pgdata',
        POSTGRES_DB: 'cantonnet',
        POSTGRES_USER: 'cnadmin',
        POSTGRES_PASSWORD: 'cnadmin',
      },
    },
    {
      apiVersion: 'apps/v1',
      kind: 'StatefulSet',
      metadata: {
        name: name,
        labels: {
          app: name,
        },
      },
      spec: {
        serviceName: name,
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
                image: 'postgres:14',
                envFrom: [
                  {
                    configMapRef: {
                      name: name + '-configuration',
                    },
                  },
                ],
                ports: [
                  {
                    containerPort: 5432,
                    name: 'postgresdb',
                  },
                ],
                livenessProbe: {
                  exec: {
                    command: ['psql', '-U', 'cnadmin', '-d', 'template1', '-c', 'SELECT 1'],
                  },
                },
                volumeMounts: [
                  {
                    name: 'pg-data',
                    mountPath: '/var/lib/postgresql/data',
                  },
                ],
              },
            ],
          },
        },
        volumeClaimTemplates: [
          {
            metadata: {
              name: 'pg-data',
            },
            spec: {
              accessModes: ['ReadWriteOnce'],
              resources: {
                requests: {
                  storage: '10Gi',
                },
              },
            },
          },
        ],
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
        ports: [
          {
            name: 'postgresdb',
            protocol: 'TCP',
            port: 5432,
          },
        ],
      },
    },
  ],
};

{
  database:: database,
}
