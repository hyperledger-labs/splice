import * as pulumi from '@pulumi/pulumi';

import { BaseMultiNodeArgs, MultiNodeDeployment } from './multiNodeDeployment';
import { basePort } from './utils';

export class MultiValidator extends MultiNodeDeployment {
  constructor(name: string, args: BaseMultiNodeArgs, opts?: pulumi.ComponentResourceOptions) {
    const ports = Array.from({ length: args.numNodes }, (_, i) => ({
      name: `val-${i}`,
      port: basePort(i) + 3,
    }));

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
              value: 'multi-participant-svc',
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
                secretKeyRef: args.secretRef,
              },
            },
          ],
          ports: ports.map(port => ({
            name: port.name,
            containerPort: port.port,
            protocol: 'TCP',
          })),
          // TODO(#10649): These probes only check the first validator, not all of them.
          livenessProbe: {
            httpGet: {
              path: '/api/validator/livez',
              port: ports[0].port,
            },
            initialDelaySeconds: 60,
            periodSeconds: 60,
            failureThreshold: 5,
            timeoutSeconds: 10,
          },
          readinessProbe: {
            httpGet: {
              path: '/api/validator/readyz',
              port: ports[0].port,
            },
            initialDelaySeconds: 5,
            periodSeconds: 5,
            failureThreshold: 5,
            timeoutSeconds: 3,
          },
        },
        serviceSpec: { ports },
      },
      opts
    );
  }
}
