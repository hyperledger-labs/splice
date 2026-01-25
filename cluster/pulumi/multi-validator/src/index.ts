// Copyright (c) 2026 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { isDevNet } from '@lfdecentralizedtrust/splice-pulumi-common';

import { installNode } from './installNode';

async function main() {
  if (isDevNet) {
    await installNode();
  } else {
    throw new Error(
      'The multi-validator stack is only supported on devnet, for validator onboarding to SV.'
    );
  }
}

// eslint-disable-next-line @typescript-eslint/no-floating-promises
main();
