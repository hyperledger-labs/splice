// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { clusterYamlConfig } from '@lfdecentralizedtrust/splice-pulumi-common/src/config/config';
import { z } from 'zod';

export const AutomationSchema = z.object({
  automation: z
    .object({
      delegatelessAutomationExpectedTaskDuration: z.number().default(5000),
      delegatelessAutomationExpiredRewardCouponBatchSize: z.number().default(100),
    })
    .optional()
    .default({
      delegatelessAutomationExpectedTaskDuration: 5000,
      delegatelessAutomationExpiredRewardCouponBatchSize: 100,
    }),
});

export type Config = z.infer<typeof AutomationSchema>;

// eslint-disable-next-line
// @ts-ignore
const fullConfig = AutomationSchema.parse(clusterYamlConfig);
export const delegatelessAutomationExpectedTaskDuration =
  fullConfig.automation.delegatelessAutomationExpectedTaskDuration;
export const delegatelessAutomationExpiredRewardCouponBatchSize =
  fullConfig.automation.delegatelessAutomationExpiredRewardCouponBatchSize;
