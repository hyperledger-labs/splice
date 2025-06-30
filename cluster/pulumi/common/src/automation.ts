// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { clusterYamlConfig } from 'splice-pulumi-common/src/config/configLoader';
import { z } from 'zod';

// TODO(#459): remove unused flags
export const AutomationSchema = z.object({
  automation: z
    .object({
      delegatelessAutomation: z.boolean().default(true),
      expectedTaskDuration: z.number().default(5000),
      expiredRewardCouponBatchSize: z.number().default(100),
    })
    .optional()
    .default({
      delegatelessAutomation: true,
      expectedTaskDuration: 5000,
      expiredRewardCouponBatchSize: 100,
    }),
});

export type Config = z.infer<typeof AutomationSchema>;

// eslint-disable-next-line
// @ts-ignore
const fullConfig = AutomationSchema.parse(clusterYamlConfig);
export const delegatelessAutomation = fullConfig.automation.delegatelessAutomation;
export const expectedTaskDuration = fullConfig.automation.expectedTaskDuration;
export const expiredRewardCouponBatchSize = fullConfig.automation.expiredRewardCouponBatchSize;
