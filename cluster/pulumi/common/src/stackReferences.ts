import * as pulumi from '@pulumi/pulumi';

import { CLUSTER_BASENAME } from './utils';

// Reference to upstream infrastructure stack.
export const infraStack = new pulumi.StackReference(`organization/infra/infra.${CLUSTER_BASENAME}`);
