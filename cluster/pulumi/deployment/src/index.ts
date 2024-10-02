import { DecentralizedSynchronizerUpgradeConfig } from 'splice-pulumi-common';

import { gitRepoForRef } from './flux';
import { installMigrationSpecificStacks } from './stacks/migration';
import { installSpliceStacks } from './stacks/splice';

if (DecentralizedSynchronizerUpgradeConfig.active.releaseReference) {
  const mainReference = DecentralizedSynchronizerUpgradeConfig.active.releaseReference;
  const mainStackReference = gitRepoForRef('active', mainReference);

  installSpliceStacks(mainStackReference);
  installMigrationSpecificStacks(mainStackReference);
} else {
  throw new Error('No valid reference found for active migration');
}
