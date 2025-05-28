// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { DecentralizedSynchronizerUpgradeConfig } from 'splice-pulumi-common';
import { gitRepoForRef } from 'splice-pulumi-common/src/operator/flux-source';
import { createEnvRefs } from 'splice-pulumi-common/src/operator/stack';

import {
  getMigrationSpecificStacksFromMainReference,
  installMigrationSpecificStacks,
} from './stacks/migration';
import { getSpliceStacksFromMainReference, installSpliceStacks } from './stacks/splice';

if (!DecentralizedSynchronizerUpgradeConfig.active.releaseReference) {
  throw new Error('No release reference found for active migration');
}

const envRefs = createEnvRefs('deployment-env', 'operator');
const mainReference = DecentralizedSynchronizerUpgradeConfig.active.releaseReference;
const allMainRefStacks = getSpliceStacksFromMainReference().concat(
  getMigrationSpecificStacksFromMainReference()
);
const mainStackReference = gitRepoForRef('active', mainReference, allMainRefStacks);

installSpliceStacks(mainStackReference, envRefs);
installMigrationSpecificStacks(mainStackReference, envRefs);
