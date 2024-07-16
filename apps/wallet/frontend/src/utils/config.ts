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

type WalletServicesConfig = {
  validator: z.infer<typeof serviceSchema>;
};

type WalletConfig = {
  auth: z.infer<typeof authSchema>;
  testAuth?: z.infer<typeof testAuthSchema>;
  services: WalletServicesConfig;
  clusterUrl: string;
  spliceInstanceNames: z.infer<typeof spliceInstanceNamesSchema>;
};

const reader = new ConfigReader(
  z.object({
    auth: authSchema,
    testAuth: testAuthSchema.optional(),
    services: z.object({
      validator: serviceSchema,
    }),
    clusterUrl: z.string().url(),
    spliceInstanceNames: spliceInstanceNamesSchema,
  })
);

export const config: WalletConfig = reader.loadConfig();
