import * as pulumi from '@pulumi/pulumi';
import { generatePortSequence, numNodesPerInstance } from 'cn-pulumi-common';

import { BaseMultiNodeArgs, MultiNodeDeployment } from './multiNodeDeployment';

interface MultiValidatorArgs extends BaseMultiNodeArgs {
  participant: {
    address: pulumi.Output<string>;
  };
}

export class MultiValidator extends MultiNodeDeployment {
  constructor(name: string, args: MultiValidatorArgs, opts?: pulumi.ComponentResourceOptions) {
    const ports = generatePortSequence(5000, numNodesPerInstance, [{ name: 'val', id: 3 }]);

    super(
      name,
      {
        ...args,
        imageName: 'multi-validator',
        container: {
          env: [
            {
              name: 'CN_APP_DEVNET',
              value: '1',
            },
            {
              name: 'CN_APP_VALIDATOR_PARTICIPANT_ADDRESS',
              value: pulumi.interpolate`${args.participant.address}`,
            },
            {
              name: 'CN_APP_VALIDATOR_SCAN_URL',
              value: `http://scan-app.sv-1:5012`,
            },
            {
              name: 'CN_APP_VALIDATOR_SV_SPONSOR_ADDRESS',
              value: `http://sv-app.sv-1:5014`,
            },
            {
              name: 'CN_APP_POSTGRES_DATABASE_NAME',
              value: args.postgres.db,
            },
            {
              name: 'CN_APP_POSTGRES_SCHEMA',
              value: args.postgres.schema,
            },
            {
              name: 'CN_APP_POSTGRES_HOST',
              value: args.postgres.host,
            },
            {
              name: 'CN_APP_POSTGRES_PORT',
              value: args.postgres.port,
            },
            {
              name: 'CN_APP_POSTGRES_USER',
              value: 'cnadmin',
            },
            {
              name: 'CN_APP_POSTGRES_PASSWORD',
              valueFrom: {
                secretKeyRef: args.postgres.secret,
              },
            },
          ],
          ports: ports.map(port => ({
            name: port.name,
            containerPort: port.port,
            protocol: 'TCP',
          })),
          livenessProbe: {
            exec: {
              command: ['/bin/bash', '/app/health-check.sh', 'api/validator/livez'],
            },
            initialDelaySeconds: 60,
            periodSeconds: 60,
            failureThreshold: 5,
            timeoutSeconds: 10,
          },
          readinessProbe: {
            exec: {
              command: ['/bin/bash', '/app/health-check.sh', 'api/validator/readyz'],
            },
            initialDelaySeconds: 5,
            periodSeconds: 5,
            failureThreshold: 5,
            timeoutSeconds: 3,
          },
          resources: {
            requests: {
              cpu: '2',
              memory: '8Gi',
            },
            limits: {
              cpu: '8',
              memory: '32Gi',
            },
          },
        },
        serviceSpec: { ports },
      },
      opts
    );
  }
}
