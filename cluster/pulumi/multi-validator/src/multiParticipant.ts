import * as pulumi from '@pulumi/pulumi';
import { generatePortSequence } from 'cn-pulumi-common';

import { BaseMultiNodeArgs, MultiNodeDeployment } from './multiNodeDeployment';

export class MultiParticipant extends MultiNodeDeployment {
  constructor(name: string, args: BaseMultiNodeArgs, opts?: pulumi.ComponentResourceOptions) {
    const ports = generatePortSequence(5000, args.numNodes, [
      { name: 'lg', id: 1 },
      { name: 'adm', id: 2 },
    ]);

    super(
      name,
      {
        ...args,
        imageName: 'multi-participant',
        container: {
          env: [
            {
              name: 'CANTON_PARTICIPANT_POSTGRES_SERVER',
              value: args.postgres.host,
            },
            {
              name: 'CANTON_PARTICIPANT_POSTGRES_DB',
              value: args.postgres.db,
            },
            {
              name: 'CANTON_PARTICIPANT_POSTGRES_SCHEMA',
              value: args.postgres.schema,
            },
            {
              name: 'CANTON_PARTICIPANT_POSTGRES_PASSWORD',
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
        },
        serviceSpec: { ports },
      },
      opts
    );
  }
}
