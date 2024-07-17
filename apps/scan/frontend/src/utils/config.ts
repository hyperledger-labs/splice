// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { ConfigReader, serviceSchema, spliceInstanceNamesSchema } from 'common-frontend';
import { z } from 'zod';

type ScanServicesConfig = {
  scan: z.infer<typeof serviceSchema>;
};

type ScanConfig = {
  services: ScanServicesConfig;
  spliceInstanceNames: z.infer<typeof spliceInstanceNamesSchema>;
};

const reader = new ConfigReader(
  z.object({
    services: z.object({
      scan: serviceSchema,
    }),
    spliceInstanceNames: spliceInstanceNamesSchema,
  })
);

export const config: ScanConfig = reader.loadConfig();
