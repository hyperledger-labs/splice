import * as k8s from '@pulumi/kubernetes';
import { config } from 'splice-pulumi-common';

import { namespace } from '../namespace';

export const githubSecret = new k8s.core.v1.Secret('github', {
  metadata: {
    name: 'github',
    namespace: namespace.ns.metadata.name,
  },
  type: 'Opaque',
  stringData: {
    username: config.optionalEnv('GH_USER') || 'canton-network-da',
    password: config.requireEnv('GH_TOKEN'),
  },
});
