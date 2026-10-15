// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { isDevNet } from '@canton-network/splice-pulumi-common';

import { multiValidatorConfig } from './config';
import { installNode } from './installNode';

async function main() {
  // TODO(#7801) remove isDevNet when internal config is adjusted
  if (isDevNet && (multiValidatorConfig?.enabled ?? false)) {
    await installNode();
  } else {
    throw new Error(
      'The multi-validator stack is only supported on devnet, for validator onboarding to SV.'
    );
  }
}

// eslint-disable-next-line @typescript-eslint/no-floating-promises
main();
