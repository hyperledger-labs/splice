import { DecentralizedSynchronizerUpgradeConfig } from 'splice-pulumi-common';
import { gitRepoForRef } from 'splice-pulumi-common/src/operator/flux-source';
import { createEnvRefs } from 'splice-pulumi-common/src/operator/stack';

import { installMigrationSpecificStacks } from './stacks/migration';
import { installSpliceStacks } from './stacks/splice';

if (DecentralizedSynchronizerUpgradeConfig.active.releaseReference) {
  const envRefs = createEnvRefs('deployment-env', 'operator');
  const mainReference = DecentralizedSynchronizerUpgradeConfig.active.releaseReference;
  const mainStackReference = gitRepoForRef('active', mainReference);

  installSpliceStacks(mainStackReference, envRefs);
  installMigrationSpecificStacks(mainStackReference, envRefs);
} else {
  throw new Error('No valid reference found for active migration');
}
