// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { clusterYamlConfig } from 'splice-pulumi-common/src/config/configLoader';
import { z } from 'zod';

export const LoadTesterConfigSchema = z.object({
  loadTester: z
    .object({
      enable: z.boolean(),
      minRate: z.number().default(0.9),
      iterationsPerMinute: z.number().default(60),
    })
    .optional(),
});

export type LoadTesterConfig = z.infer<typeof LoadTesterConfigSchema>;

export const loadTesterConfig = LoadTesterConfigSchema.parse(clusterYamlConfig).loadTester;
