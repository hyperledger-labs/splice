import { Auth0Fetch } from 'cn-pulumi-common';

import { auth0Cfg } from './auth0cfg';
import { installNode } from './installNode';
import { SV_NAME, SV_NAMESPACE, validatorWalletUserName } from './utils';

async function main() {
  const auth0Fetch = new Auth0Fetch(auth0Cfg);

  await auth0Fetch.loadAuth0Cache();

  const svAppConfig = {
    onboardingName: SV_NAME,
    cometBftConnectionUri: 'http://cometbft-cometbft-rpc:26657',
  };
  const validatorAppConfig = {
    walletUserName: validatorWalletUserName,
  };

  await installNode(auth0Fetch, SV_NAMESPACE, svAppConfig, validatorAppConfig);

  await auth0Fetch.saveAuth0Cache();
}

// eslint-disable-next-line @typescript-eslint/no-floating-promises
main();
