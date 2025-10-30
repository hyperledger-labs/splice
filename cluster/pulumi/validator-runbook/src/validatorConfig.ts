// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { config } from '@lfdecentralizedtrust/splice-pulumi-common';
import {
  allValidatorsConfig,
  ValidatorConfig,
} from '@lfdecentralizedtrust/splice-pulumi-common-validator';
import util from 'node:util';

function getValidatorConfig(validatorName: string) {
  const config = allValidatorsConfig[validatorName] as ValidatorConfig;
  if (!config) {
    throw new Error(`No configuration found for validator ${validatorName}`);
  }
  return config;
}

export const validatorName = config.requireEnv('SPLICE_VALIDATOR_RUNBOOK_VALIDATOR_NAME');
export const validatorConfig = getValidatorConfig(validatorName);

console.error(
  `Loaded validator ${validatorConfig} configuration`,
  util.inspect(validatorConfig, {
    depth: null,
    maxStringLength: null,
  })
);
