local database(name, config, namespace=null) = {
  ports: [],
  deploymentObjects: [
    {
      apiVersion: "v1",
      kind: "ConfigMap",
      metadata: {
        name: name + "-configuration",
        labels: {
          app: name,
          clusterName: config.clusterName,
        },
        [if namespace != null then "namespace"]: namespace,
      },
      data: {
        PGDATA: "/var/lib/postgresql/data/pgdata",
        POSTGRES_DB: "cantonnet",
        POSTGRES_USER: "cnadmin",
        POSTGRES_PASSWORD: "cnadmin",
      },
    },
    {
      apiVersion: "apps/v1",
      kind: "StatefulSet",
      metadata: {
        name: name,
        labels: {
          app: name,
          clusterName: config.clusterName,
        },
        [if namespace != null then "namespace"]: namespace,
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
              clusterName: config.clusterName,
            },
            [if namespace != null then "namespace"]: namespace,
          },
          spec: {
            containers: [
              {
                name: name,
                image: "postgres:14",
                envFrom: [
                  {
                    configMapRef: {
                      name: name + "-configuration",
                    },
                  },
                ],
                ports: [
                  {
                    containerPort: 5432,
                    name: "postgresdb",
                  },
                ],
                livenessProbe: {
                  exec: {
                    command: ["psql", "-U", "cnadmin", "-d", "template1", "-c", "SELECT 1"],
                  },
                },
                resources: {
                  requests: {
                    memory: config.postgresMemoryMib + "Mi",
                    cpu: config.postgresCpu,
                  },
                  limits: {
                    memory: config.postgresMemoryMib + "Mi",
                    cpu: config.postgresCpu,
                  },
                },
                volumeMounts: [
                  {
                    name: "pg-data",
                    mountPath: "/var/lib/postgresql/data",
                  },
                ],
              },
            ],
          },
        },
        volumeClaimTemplates: [
          {
            metadata: {
              name: "pg-data",
              labels: {
                clusterName: config.clusterName,
              },
            },
            spec: {
              accessModes: ["ReadWriteOnce"],
              storageClassName: "standard-rwo",
              resources: {
                requests: {
                  storage: config.ledgerDatabaseGib + "Gi",
                },
              },
            },
          },
        ],
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
            name: "postgresdb",
            protocol: "TCP",
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
