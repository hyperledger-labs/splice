// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { config } from './config';

export * from './config';

export const ENTRY_NAME_SUFFIX = `.unverified.${config.spliceInstanceNames.nameServiceNameAcronym.toLowerCase()}`;
export const toFullEntryName: (name: string, suffix: string) => string = (
  name: string,
  suffix: string
) => `${name}${suffix}`;
