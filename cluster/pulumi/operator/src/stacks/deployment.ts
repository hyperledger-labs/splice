// Copyright (c) 2026 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { GitFluxRef } from '@lfdecentralizedtrust/splice-pulumi-common/src/operator/flux-source';
import {
  createStackCR,
  EnvRefs,
} from '@lfdecentralizedtrust/splice-pulumi-common/src/operator/stack';
import { Secret } from '@pulumi/kubernetes/core/v1';

import { spliceEnvConfig } from '../../../common/src/config/envConfig';
import { flux } from '../flux';
import { namespace } from '../namespace';
import { operator } from '../operator';

export function installDeploymentStack(reference: GitFluxRef, envRefs: EnvRefs): void {
  const credentialsSecret = new Secret('operator-gke-credentials', {
    metadata: {
      name: 'operator-gke-credentials',
      namespace: namespace.ns.metadata.name,
    },
    type: 'Opaque',
    stringData: {
      googleCredentials: spliceEnvConfig.requireEnv('GOOGLE_CREDENTIALS'),
    },
  });

  createStackCR(
    'deployment',
    'deployment',
    namespace.logicalName,
    false,
    reference,
    envRefs,
    credentialsSecret,
    {},
    [operator, flux]
  );
}
