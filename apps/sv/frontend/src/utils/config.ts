// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import {
  ConfigReader,
  authSchema,
  testAuthSchema,
  serviceSchema,
  spliceInstanceNamesSchema,
} from 'common-frontend';
import { z } from 'zod';

type SvServicesConfig = {
  sv: z.infer<typeof serviceSchema>;
};

type SvConfig = {
  auth: z.infer<typeof authSchema>;
  testAuth?: z.infer<typeof testAuthSchema>;
  spliceInstanceNames: z.infer<typeof spliceInstanceNamesSchema>;
  services: SvServicesConfig;
};

const reader = new ConfigReader(
  z.object({
    auth: authSchema,
    testAuth: testAuthSchema.optional(),
    spliceInstanceNames: spliceInstanceNamesSchema,
    services: z.object({
      sv: serviceSchema,
    }),
  })
);

export const config: SvConfig = reader.loadConfig();
