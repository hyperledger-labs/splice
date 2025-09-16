// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { deployedValidators } from '@lfdecentralizedtrust/splice-pulumi-common-validator/src/config';

import { Operation, PulumiAbortController, stack } from '../pulumi';
import { upStack } from '../pulumiOperations';

export function runAllValidatorsUp(abortController: PulumiAbortController): Operation[] {
  const validatorsToRunFor = deployedValidators.map(validator => {
    return {
      validator: validator,
      stackName: validator === 'validator-runbook' ? validator : `validators.${validator}`,
    };
  });
  return validatorsToRunFor.map(validator => {
    console.error(`Running up for validator ${JSON.stringify(validator)}`);
    return {
      name: `up-${validator.validator}`,
      promise: stack('validator-runbook', validator.stackName, false, {
        SPLICE_VALIDATOR_RUNBOOK_VALIDATOR_NAME: validator.validator,
        // eslint-disable-next-line promise/prefer-await-to-then
      }).then(stack => upStack(stack, abortController)),
    };
  });
}
