// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { z } from 'zod';

import { spliceEnvConfig } from './envConfig';

export * from './configSchema';
export * from './kms';
export { spliceEnvConfig as config } from './envConfig';

export const DeploySvRunbook = spliceEnvConfig.envFlag('SPLICE_DEPLOY_SV_RUNBOOK', false);
export const DeployValidatorRunbook = spliceEnvConfig.envFlag(
  'SPLICE_DEPLOY_VALIDATOR_RUNBOOK',
  false
);

export const clusterProdLike = spliceEnvConfig.envFlag('GCP_CLUSTER_PROD_LIKE');

// During development we often overwrite the same tag so we use imagePullPolicy: Always.
// Outside of development, we use the default which corresponds to IfNotPresent
// (unless the tag is LATEST which it never is in our setup).
export const imagePullPolicy = clusterProdLike ? {} : { imagePullPolicy: 'Always' };

export const supportsSvRunbookReset = spliceEnvConfig.envFlag('SUPPORTS_SV_RUNBOOK_RESET', false);

export const isMainNet = spliceEnvConfig.envFlag('IS_MAINNET', false);
export const isDevNet = spliceEnvConfig.envFlag('IS_DEVNET', true) && !isMainNet;
export const clusterSmallDisk = spliceEnvConfig.envFlag('CLUSTER_SMALL_DISK', false);
export const failOnAppVersionMismatch: boolean = spliceEnvConfig.envFlag(
  'FAIL_ON_APP_VERSION_MISMATCH',
  true
);

export const LogLevelSchema = z.enum(['DEBUG', 'INFO', 'WARN', 'ERROR']);
export type LogLevel = z.infer<typeof LogLevelSchema>;
