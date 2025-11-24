// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as k8s from '@pulumi/kubernetes';
import * as pulumi from '@pulumi/pulumi';
import {
  appsAffinityAndTolerations,
  DOCKER_REPO,
  imagePullPolicy,
  jmxOptions,
  numNodesPerInstance,
} from '@lfdecentralizedtrust/splice-pulumi-common';
import { ServiceMonitor } from '@lfdecentralizedtrust/splice-pulumi-common/src/metrics';
import _ from 'lodash';

import { Version } from '../version';
import { EnvironmentVariable, multiValidatorConfig } from './config';

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
    livenessProbe: k8s.types.input.core.v1.Probe;
    readinessProbe: k8s.types.input.core.v1.Probe;
    resources?: k8s.types.input.core.v1.ResourceRequirements;
    volumeMounts?: k8s.types.input.core.v1.VolumeMount[];
  };
  serviceSpec: k8s.types.input.core.v1.ServiceSpec;
  volumes?: k8s.types.input.core.v1.Volume[];
}

export class MultiNodeDeployment extends pulumi.ComponentResource {
  deployment: k8s.apps.v1.Deployment;
  service: k8s.core.v1.Service;

  constructor(
    name: string,
    args: MultiNodeDeploymentArgs,
    opts?: pulumi.ComponentResourceOptions,
    javaOpts?: string,
    extraEnvVars?: EnvironmentVariable[]
  ) {
    super('canton:network:Deployment', name, {}, opts);

    const newOpts = { ...opts, parent: this, dependsOn: [args.namespace] as pulumi.Resource[] };
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
              securityContext: {
                runAsUser: 1001,
                runAsGroup: 1001,
                fsGroup: 1001,
              },
              containers: [
                {
                  name: args.imageName,
                  image: `${DOCKER_REPO}/${args.imageName}:${Version}`,
                  ...imagePullPolicy,
                  ...args.container,
                  ports: args.container.ports.concat([
                    {
                      name: 'metrics',
                      containerPort: 10013,
                    },
                    {
                      name: 'jmx',
                      containerPort: 9010,
                    },
                  ]),
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
                        `-XX:MaxRAMPercentage=80 -XX:InitialRAMPercentage=80 -Dscala.concurrent.context.minThreads=16 ${javaOpts || ''} ` +
                        jmxOptions(),
                    },
                  ]
                    .concat(
                      multiValidatorConfig?.requiresOnboardingSecret
                        ? [
                            {
                              name: 'SPLICE_APP_VALIDATOR_NEEDS_ONBOARDING_SECRET',
                              value: 'true',
                            },
                          ]
                        : []
                    )
                    .concat(extraEnvVars || []),
                },
              ],
              volumes: args.volumes,
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
              ...appsAffinityAndTolerations,
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
        spec: {
          ..._.merge(args.serviceSpec, {
            selector: {
              app: name,
            },
          }),
          ...{
            ports: pulumi
              .all([
                args.serviceSpec.ports,
                {
                  name: 'metrics',
                  port: 10013,
                },
                {
                  name: 'jmx',
                  port: 9010,
                },
              ])
              .apply(([ports, metricPort, jmxPort]) =>
                ports ? ports.concat([metricPort, jmxPort]) : [metricPort, jmxPort]
              ),
          },
        },
      },
      { ...newOpts, dependsOn: newOpts.dependsOn.concat([this.deployment]) }
    );

    const monitor = new ServiceMonitor(
      `${name}-service-monitor`,
      { app: name },
      'metrics',
      args.namespace.metadata.name,
      newOpts
    );
    this.registerOutputs({
      deployment: this.deployment,
      service: this.service,
      serviceMonitor: monitor,
    });
  }
}
