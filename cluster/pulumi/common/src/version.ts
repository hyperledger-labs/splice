// Copyright (c) 2026 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { activeVersion } from '@lfdecentralizedtrust/splice-pulumi-common';

export function versionFromDefault(): string {
  if (activeVersion.type == 'remote') {
    return activeVersion.version;
  } else {
    throw new Error('No valid version found; "local" versions not supported');
  }
}
