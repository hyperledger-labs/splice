import {
  mustInstallSplitwell,
  mustInstallValidator1,
} from 'splice-pulumi-common-validator/src/validators';

import { awaitAllOrThrowAllExceptions, downStack, PulumiAbortController, stack } from './pulumi';

import { cancelAllTheStacks, downAllTheStacks } from "./sv-canton/pulumiHelper";
import { runStacksCancel, runStacksRefresh } from "./pulumiHelper";

async function runStacksDown() {
  const mainStack = await stack('canton-network', 'canton-network', true, {});
  const operations: Promise<void>[] = [];
  const abortController = new PulumiAbortController();
  operations.push(downStack(mainStack, abortController));
  if (mustInstallValidator1) {
    const validator1 = await stack('validator1', 'validator1', true, {});
    operations.push(downStack(validator1, abortController));
  }
  if (mustInstallSplitwell) {
    const splitwell = await stack('splitwell', 'splitwell', true, {});
    operations.push(downStack(splitwell, abortController));
  }
  const multiValidatorStack = await stack('multi-validator', 'multi-validator', true, {});
  operations.push(downStack(multiValidatorStack, abortController));
  const svRunbookStack = await stack('sv-runbook', 'sv-runbook', true, {});
  operations.push(downStack(svRunbookStack, abortController));
  const validatorRunbookStack = await stack('validator-runbook', 'validator-runbook', true, {});
  operations.push(downStack(validatorRunbookStack, abortController));
  const deploymentStack = await stack('deployment', 'deployment', true, {});
  operations.push(downStack(deploymentStack, abortController));
  await awaitAllOrThrowAllExceptions(operations);
}

function runAllStacksDown(cancelStacks: boolean = true) {
  if (cancelStacks) {
    runStacksCancel().catch(e => {
      console.error(e);
    });
  }
  runStacksDown().catch(e => {
    console.error(e);
    if ("the stack is currently locked" in e) {
      runStacksCancel().catch(e => {
        console.error(e);
      });
      runStacksRefresh().catch(e => {
        console.error(e);
      });
      runAllStacksDown(false)
    } else {
      console.error("Failed uninstalling stack, aborting")
    }
    process.exit(1);
  });
  cancelAllTheStacks().catch(e => {
      console.error(e);
  });
  downAllTheStacks().catch(e => {
    console.error(e);
    process.exit(1);
  });
}

runAllStacksDown()
