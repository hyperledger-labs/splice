// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { z } from 'zod';

import { clusterYamlConfig } from './configLoader';

export const SplitwellConfigSchema = z.object({
  splitwell: z
    .object({
      maxDarVersion: z.string().optional(),
    })
    .optional(),
});

export type Config = z.infer<typeof SplitwellConfigSchema>;

export const splitwellConfig = SplitwellConfigSchema.parse(clusterYamlConfig).splitwell;
