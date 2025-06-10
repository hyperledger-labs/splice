// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { core } from '@pulumi/kubernetes';
import { DecentralizedSynchronizerUpgradeConfig } from 'splice-pulumi-common';
import { gitRepoForRef } from 'splice-pulumi-common/src/operator/flux-source';
import { createEnvRefs } from 'splice-pulumi-common/src/operator/stack';

import { spliceEnvConfig } from '../../common/src/config/envConfig';
import {
  getMigrationSpecificStacksFromMainReference,
  installMigrationSpecificStacks,
} from './stacks/migration';
import { getSpliceStacksFromMainReference, installSpliceStacks } from './stacks/splice';

if (!DecentralizedSynchronizerUpgradeConfig.active.releaseReference) {
  throw new Error('No release reference found for active migration');
}

const namespace = 'operator';
const envRefs = createEnvRefs('deployment-env', 'operator');
const mainReference = DecentralizedSynchronizerUpgradeConfig.active.releaseReference;
const allMainRefStacks = getSpliceStacksFromMainReference().concat(
  getMigrationSpecificStacksFromMainReference()
);
const mainStackReference = gitRepoForRef('active', mainReference, allMainRefStacks);
const credentialsSecret = new core.v1.Secret('gke-credentials', {
  metadata: {
    name: 'gke-credentials',
    namespace: namespace,
  },
  type: 'Opaque',
  stringData: {
    googleCredentials: spliceEnvConfig.requireEnv('GOOGLE_CREDENTIALS'),
  },
});

installSpliceStacks(mainStackReference, envRefs, namespace, credentialsSecret);
installMigrationSpecificStacks(mainStackReference, envRefs, namespace, credentialsSecret);
