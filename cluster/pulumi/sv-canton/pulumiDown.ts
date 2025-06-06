import { config } from 'splice-pulumi-common/src/config';

import { awaitAllOrThrowAllExceptions, Operation, PulumiAbortController } from '../pulumi';
import { downStack } from '../pulumiOperations';
import { runSvCantonForAllMigrations } from './pulumi';

// used in CI clusters that run HDM to ensure everything is cleaned up
export const extraMigrationsToReset =
  config
    .optionalEnv('GLOBAL_DOMAIN_SV_CANTON_EXTRA_MIGRATIONS_RESET')
    ?.split(',')
    .map(id => parseInt(id)) || [];

export function startDownOperationsForCantonStacks(
  abortController: PulumiAbortController
): Operation[] {
  return runSvCantonForAllMigrations(
    'down',
    stack => {
      return downStack(stack, abortController);
    },
    false,
    true,
    extraMigrationsToReset
  );
}

export async function downAllTheCantonStacks(
  abortController: PulumiAbortController
): Promise<void> {
  await awaitAllOrThrowAllExceptions(startDownOperationsForCantonStacks(abortController));
}
