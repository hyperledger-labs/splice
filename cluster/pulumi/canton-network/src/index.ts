import { Auth0Fetch } from './auth0';
import { installCluster } from './installCluster';

async function main() {
  const auth0Fetch = new Auth0Fetch();

  await auth0Fetch.loadAuth0Cache();

  await installCluster(auth0Fetch);

  await auth0Fetch.saveAuth0Cache();
}

main();
