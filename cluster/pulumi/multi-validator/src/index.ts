import { isDevNet } from 'splice-pulumi-common';

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
