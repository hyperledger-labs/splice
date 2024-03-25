import * as k8s from '@pulumi/kubernetes';
import * as pulumi from '@pulumi/pulumi';
import { numNodesPerInstance, requireEnv } from 'cn-pulumi-common';
import _ from 'lodash';

export interface BaseMultiNodeArgs {
  namespace: k8s.core.v1.Namespace;
  postgres: {
    host: string;
    schema: string;
    port: string;
    db: string;
    secret: { name: string; key: string };
  };
}

interface MultiNodeDeploymentArgs extends BaseMultiNodeArgs {
  imageName: string;
  container: {
    env: k8s.types.input.core.v1.EnvVar[];
    ports: k8s.types.input.core.v1.ContainerPort[];
    livenessProbe?: k8s.types.input.core.v1.Probe;
    readinessProbe?: k8s.types.input.core.v1.Probe;
    resources: k8s.types.input.core.v1.ResourceRequirements;
  };
  serviceSpec: k8s.types.input.core.v1.ServiceSpec;
}

export class MultiNodeDeployment extends pulumi.ComponentResource {
  deployment: k8s.apps.v1.Deployment;
  service: k8s.core.v1.Service;

  constructor(name: string, args: MultiNodeDeploymentArgs, opts?: pulumi.ComponentResourceOptions) {
    super('canton:network:Deployment', name, {}, opts);

    const newOpts = { ...opts, parent: this, dependsOn: [args.namespace] };
    const zeroPad = (num: number, places: number) => String(num).padStart(places, '0');

    this.deployment = new k8s.apps.v1.Deployment(
      name,
      {
        metadata: {
          namespace: args.namespace.metadata.name,
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
              },
            },
            spec: {
              containers: [
                {
                  name: args.imageName,
                  image: `us-central1-docker.pkg.dev/da-cn-shared/cn-images/${
                    args.imageName
                  }:${requireEnv('IMAGE_TAG')}`,
                  imagePullPolicy: 'Always',
                  ...args.container,
                  env: [
                    ...(args.container.env || []),
                    {
                      name: 'NUM_NODES',
                      value: `${numNodesPerInstance}`,
                    },
                    {
                      name: 'VALIDATOR_USERNAME_PREFIX',
                      value: 'validator_user',
                    },
                    {
                      name: 'AUTH_TARGET_AUDIENCE',
                      value: `https://canton.network.global`,
                    },
                    {
                      name: 'JAVA_TOOL_OPTIONS',
                      value:
                        '-XX:MaxRAMPercentage=75 -XX:InitialRAMPercentage=75 -Dscala.concurrent.context.minThreads=16',
                    },
                  ],
                },
              ],
              initContainers: [
                {
                  name: 'pg-init',
                  image: 'postgres:14',
                  env: [
                    {
                      name: 'PGPASSWORD',
                      valueFrom: {
                        secretKeyRef: args.postgres.secret,
                      },
                    },
                  ],
                  command: [
                    'bash',
                    '-c',
                    `
                        function createDb() {
                          local dbname="$1"

                          until errmsg=$(psql -h ${
                            args.postgres.host
                          } --username=cnadmin --dbname=cantonnet -c "create database $dbname" 2>&1); do
                          if [[ $errmsg == *"already exists"* ]]; then
                              echo "Database $dbname already exists. Done."
                              break
                          fi

                          echo "trying to create postgres database $dbname, last error: $errmsg";
                          sleep 2;
                          done
                        }

                        ${Array.from(
                          { length: numNodesPerInstance },
                          (_, i) => `createDb ${args.postgres.db}_${zeroPad(i, 2)}`
                        ).join('\n')}
                      `,
                  ],
                },
              ],
            },
          },
        },
      },
      newOpts
    );

    this.service = new k8s.core.v1.Service(
      name,
      {
        metadata: {
          namespace: args.namespace.metadata.name,
          name: name,
          labels: {
            app: name,
          },
        },
        spec: _.merge(args.serviceSpec, {
          selector: {
            app: name,
          },
        }),
      },
      newOpts
    );

    this.registerOutputs({ deployment: this.deployment, service: this.service });
  }
}
