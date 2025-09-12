// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { clusterYamlConfig } from '@lfdecentralizedtrust/splice-pulumi-common/src/config/configLoader';
import util from 'node:util';
import { z } from 'zod';

export const LoadTesterAdaptiveScenarioConfigSchema = z.object({
  maxVUs: z.number().default(50),
  minVUs: z.number().default(0),
  enabled: z.boolean().default(false),
  scaleDownStep: z.number().default(5),
});

export const LoadTesterConfigSchema = z.object({
  loadTester: z
    .object({
      enable: z.boolean(),
      chartVersion: z.string().optional(),
      minRate: z.number().default(0.9),
      iterationsPerMinute: z.number().default(60),
      adaptiveScenario: LoadTesterAdaptiveScenarioConfigSchema.default({}),
    })
    .optional(),
});

export const loadTesterConfig = LoadTesterConfigSchema.parse(clusterYamlConfig).loadTester;

console.error(
  'Loaded load tester configuration',
  util.inspect(loadTesterConfig, {
    depth: null,
    maxStringLength: null,
  })
);
