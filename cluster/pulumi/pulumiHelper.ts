import {
	mustInstallSplitwell,
	mustInstallValidator1,
} from 'splice-pulumi-common-validator/src/validators';

import { awaitAllOrThrowAllExceptions, PulumiAbortController, refreshStack, stack } from './pulumi';

export async function runStacksCancel() {
	const mainStack = await stack('canton-network', 'canton-network', true, {});
	const operations: Promise<void>[] = [];
	operations.push(mainStack.cancel());
	if (mustInstallValidator1) {
		const validator1 = await stack('validator1', 'validator1', true, {});
		operations.push(validator1.cancel());
	}
	if (mustInstallSplitwell) {
		const splitwell = await stack('splitwell', 'splitwell', true, {});
		operations.push(splitwell.cancel());
	}
	const multiValidatorStack = await stack('multi-validator', 'multi-validator', true, {});
	operations.push(multiValidatorStack.cancel());
	const svRunbookStack = await stack('sv-runbook', 'sv-runbook', true, {});
	operations.push(svRunbookStack.cancel());
	const validatorRunbookStack = await stack('validator-runbook', 'validator-runbook', true, {});
	operations.push(validatorRunbookStack.cancel());
	const deploymentStack = await stack('deployment', 'deployment', true, {});
	operations.push(deploymentStack.cancel());
	await awaitAllOrThrowAllExceptions(operations);
}


export async function runStacksRefresh() {
	const mainStack = await stack('canton-network', 'canton-network', true, {});
	const operations: Promise<void>[] = [];
	const abortController = new PulumiAbortController();
	operations.push(refreshStack(mainStack, abortController));
	const validator1 = await stack('validator1', 'validator1', true, {});
	operations.push(refreshStack(validator1, abortController));
	const splitwell = await stack('splitwell', 'splitwell', true, {});
	operations.push(refreshStack(splitwell, abortController));
	const multiValidatorStack = await stack('multi-validator', 'multi-validator', true, {});
	operations.push(refreshStack(multiValidatorStack, abortController));
	const svRunbookStack = await stack('sv-runbook', 'sv-runbook', true, {});
	operations.push(refreshStack(svRunbookStack, abortController));
	const validatorRunbookStack = await stack('validator-runbook', 'validator-runbook', true, {});
	operations.push(refreshStack(validatorRunbookStack, abortController));
	const deploymentStack = await stack('deployment', 'deployment', true, {});
	operations.push(refreshStack(deploymentStack, abortController));
	await awaitAllOrThrowAllExceptions(operations);
}
