// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import util from 'node:util';
import { config } from 'splice-pulumi-common';

import { allValidatorsConfig, ValidatorConfig } from './validatorsConfig';

function getValidatorConfig(validatorName: string): ValidatorConfig {
  const config = allValidatorsConfig[validatorName] as ValidatorConfig;
  if (!config) {
    throw new Error(`No configuration found for validator ${validatorName}`);
  }
  return config;
}

const validatorName = config.requireEnv('SPLICE_VALIDATOR_RUNBOOK_VALIDATOR_NAME');
export const validatorConfig = getValidatorConfig(validatorName);

console.error(
  `Loaded validator ${validatorConfig} configuration`,
  util.inspect(validatorConfig, {
    depth: null,
    maxStringLength: null,
  })
);
