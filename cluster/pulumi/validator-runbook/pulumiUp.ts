// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { DeployValidatorRunbook } from '@lfdecentralizedtrust/splice-pulumi-common';

import { Operation, PulumiAbortController, stack } from '../pulumi';
import { upStack } from '../pulumiOperations';
import { allValidatorsConfig } from './src/validatorsConfig';

export function runAllValidatorsUp(abortController: PulumiAbortController): Operation[] {
  const allValidators = Object.keys(allValidatorsConfig);
  const validatorsToRunFor = (
    DeployValidatorRunbook
      ? allValidators
      : allValidators.filter(validator => validator !== 'validator-runbook')
  ).map(validator => {
    return {
      validator: validator,
      stackName: validator === 'validator-runbook' ? validator : `validators.${validator}`,
    };
  });
  return validatorsToRunFor.map(validator => {
    return {
      name: `up-${validator}`,
      promise: stack('validator-runbook', validator.stackName, false, {
        SPLICE_VALIDATOR_RUNBOOK_VALIDATOR_NAME: validator.validator,
        // eslint-disable-next-line promise/prefer-await-to-then
      }).then(stack => upStack(stack, abortController)),
    };
  });
}
