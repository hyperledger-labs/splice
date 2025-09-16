// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { activeVersion } from '@lfdecentralizedtrust/splice-pulumi-common';

export const Version = versionFromDefault();

function versionFromDefault() {
  if (activeVersion.type == 'remote') {
    return activeVersion.version;
  } else {
    throw new Error('No valid version found; "local" versions not supported');
  }
}
