// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import {
  ConfigReader,
  authSchema,
  spliceInstanceNamesSchema,
  serviceSchema,
  testAuthSchema,
} from 'common-frontend';
import { z } from 'zod';

const walletSchema = z.object({
  uiUrl: z.string().url(),
});

type AnsServicesConfig = {
  wallet: z.infer<typeof walletSchema>;
  validator: z.infer<typeof serviceSchema>;
};

type AnsConfig = {
  auth: z.infer<typeof authSchema>;
  testAuth?: z.infer<typeof testAuthSchema>;
  services: AnsServicesConfig;
  spliceInstanceNames: z.infer<typeof spliceInstanceNamesSchema>;
};

const reader = new ConfigReader(
  z.object({
    auth: authSchema,
    testAuth: testAuthSchema.optional(),
    services: z.object({
      wallet: z.object({
        uiUrl: z.string().url(),
      }),
      validator: serviceSchema,
    }),
    spliceInstanceNames: spliceInstanceNamesSchema,
  })
);

export const config: AnsConfig = reader.loadConfig();
