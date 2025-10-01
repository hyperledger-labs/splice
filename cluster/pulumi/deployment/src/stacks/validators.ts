// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as k8s from '@pulumi/kubernetes';
import {
  deployedValidators,
  validatorRunbookStackName,
} from '@lfdecentralizedtrust/splice-pulumi-common-validator';
import { GitFluxRef } from '@lfdecentralizedtrust/splice-pulumi-common/src/operator/flux-source';
import {
  createStackCR,
  EnvRefs,
} from '@lfdecentralizedtrust/splice-pulumi-common/src/operator/stack';

import { config } from '../../../common';

export function installAllValidatorStacks(
  reference: GitFluxRef,
  envRefs: EnvRefs,
  namespace: string,
  gcpSecret: k8s.core.v1.Secret
): void {
  const validatorStacksToCreate = deployedValidators.map(validator => {
    return {
      validator: validator,
      stackName: validatorRunbookStackName(validator),
    };
  });
  validatorStacksToCreate.forEach(validator => {
    createStackCR(
      validator.stackName,
      'validator-runbook',
      namespace,
      config.envFlag('SUPPORTS_VALIDATOR_RUNBOOK_RESET'),
      reference,
      envRefs,
      gcpSecret,
      {
        SPLICE_VALIDATOR_RUNBOOK_VALIDATOR_NAME: validator.validator,
      }
    );
  });
}
