import { Auth0Fetch } from 'cn-pulumi-common';

import { auth0Cfg } from './auth0cfg';
import { installNode } from './installNode';

// TODO(#8008): Reduce duplication from sv-runbook stack
async function main() {
  const auth0Fetch = new Auth0Fetch(auth0Cfg);

  await auth0Fetch.loadAuth0Cache();

  await installNode(auth0Fetch);

  await auth0Fetch.saveAuth0Cache();
}

main();
