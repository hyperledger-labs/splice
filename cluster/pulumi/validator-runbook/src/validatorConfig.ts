// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as pulumi from '@pulumi/pulumi';
import { config } from '@canton-network/splice-pulumi-common';
import {
  allValidatorsConfig,
  ValidatorConfig,
} from '@canton-network/splice-pulumi-common-validator';
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

void pulumi.log.debug(
  `Loaded validator ${validatorName} configuration ${util.inspect(validatorConfig, {
    depth: null,
    maxStringLength: null,
  })}`
);
