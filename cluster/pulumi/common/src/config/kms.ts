// Copyright (c) 2026 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { z } from 'zod';

import { GcpProject, GcpZone } from './gcpConfig';

export const KmsConfigSchema = z.object({
  type: z.string().default('gcp'),
  locationId: z.string().default(GcpZone!),
  projectId: z.string().default(GcpProject),
  // The keyring must already exist; create it manually if necessary.
  keyRingId: z.string(),
});

export type KmsConfig = z.infer<typeof KmsConfigSchema>;
