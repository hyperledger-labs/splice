import { Auth0Fetch } from 'cn-pulumi-common';

import { auth0Cfg } from './auth0cfg';
import { installCluster } from './installCluster';

async function main() {
  const auth0Fetch = new Auth0Fetch(auth0Cfg);

  await auth0Fetch.loadAuth0Cache();

  await installCluster(auth0Fetch);

  await auth0Fetch.saveAuth0Cache();
}

main();
