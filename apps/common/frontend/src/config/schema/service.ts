// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { z } from 'zod';

export const serviceSchema = z.object({
  url: z.string().url(),
});

export const walletSchema = z.object({
  uiUrl: z.string().url(),
});

export const spliceInstanceNamesSchema = z.object({
  networkName: z.string(),
  networkFaviconUrl: z.string().url(),
  amuletName: z.string(),
  amuletNameAcronym: z.string(),
  nameServiceName: z.string(),
  nameServiceNameAcronym: z.string(),
});
