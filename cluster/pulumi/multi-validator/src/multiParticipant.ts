import * as pulumi from '@pulumi/pulumi';

import { BaseMultiNodeArgs, MultiNodeDeployment } from './multiNodeDeployment';
import { basePort } from './utils';

export class MultiParticipant extends MultiNodeDeployment {
  constructor(name: string, args: BaseMultiNodeArgs, opts?: pulumi.ComponentResourceOptions) {
    const ports = Array.from({ length: args.numNodes }, (_, i) => [
      { name: `lg-${i}`, port: basePort(i) + 1 },
      { name: `adm-${i}`, port: basePort(i) + 2 },
    ]).flat();

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
