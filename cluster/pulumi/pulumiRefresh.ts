// Copyright (c) 2026 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { runSvCantonForAllMigrations } from '@lfdecentralizedtrust/splice-pulumi-sv-canton/pulumi';

import { awaitAllOrThrowAllExceptions, Operation, PulumiAbortController, stack } from './pulumi';
import { refreshOperation, refreshStack } from './pulumiOperations';

const abortController = new PulumiAbortController();

export async function runStacksRefresh(): Promise<void> {
  const mainStack = await stack('canton-network', 'canton-network', true, {});
  let operations: Operation[] = [];
  operations.push(refreshOperation(mainStack, abortController));
  const validator1 = await stack('validator1', 'validator1', true, {});
  operations.push(refreshOperation(validator1, abortController));
  const infra = await stack('infra', 'infra', true, {});
  operations.push(refreshOperation(infra, abortController));
  const splitwell = await stack('splitwell', 'splitwell', true, {});
  operations.push(refreshOperation(splitwell, abortController));
  const multiValidatorStack = await stack('multi-validator', 'multi-validator', true, {});
  operations.push(refreshOperation(multiValidatorStack, abortController));
  const svRunbookStack = await stack('sv-runbook', 'sv-runbook', true, {});
  operations.push(refreshOperation(svRunbookStack, abortController));
  const validatorRunbookStack = await stack('validator-runbook', 'validator-runbook', true, {});
  operations.push(refreshOperation(validatorRunbookStack, abortController));
  const deploymentStack = await stack('deployment', 'deployment', true, {});
  operations.push(refreshOperation(deploymentStack, abortController));
  const operatorStack = await stack('operator', 'operator', true, {});
  operations.push(refreshOperation(operatorStack, abortController));
  operations = operations.concat(
    runSvCantonForAllMigrations(
      'refresh',
      stack => {
        return refreshStack(stack, abortController);
      },
      false,
      true
    )
  );
  await awaitAllOrThrowAllExceptions(operations);
}

runStacksRefresh().catch(e => {
  console.error(e);
  process.exit(1);
});
