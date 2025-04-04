import { DecentralizedSynchronizerUpgradeConfig } from 'splice-pulumi-common';
import { gitRepoForRef } from 'splice-pulumi-common/src/operator/flux-source';
import { createEnvRefs } from 'splice-pulumi-common/src/operator/stack';

import {
  getMigrationSpecificStacksFromMainReference,
  installMigrationSpecificStacks,
} from './stacks/migration';
import { getSpliceStacksFromMainReference, installSpliceStacks } from './stacks/splice';

if (DecentralizedSynchronizerUpgradeConfig.active.releaseReference) {
  const envRefs = createEnvRefs('deployment-env', 'operator');
  const mainReference = DecentralizedSynchronizerUpgradeConfig.active.releaseReference;
  const allMainRefStacks = getSpliceStacksFromMainReference().concat(
    getMigrationSpecificStacksFromMainReference()
  );
  const mainStackReference = gitRepoForRef('active', mainReference, allMainRefStacks);

  installSpliceStacks(mainStackReference, envRefs);
  installMigrationSpecificStacks(mainStackReference, envRefs);
} else {
  throw new Error('No valid reference found for active migration');
}
