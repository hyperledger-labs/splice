// Need to import this by path and not through the module, so the module is not
// initialized when we don't want it to (to avoid pulumi configs trying to being read here)
import { svRunbookConfig } from 'splice-pulumi-common-sv';

import {
  initDumpConfig,
  SecretsFixtureMap,
  svRunbookAuth0Config,
} from '../common/src/dump-config-common';

async function main() {
  await initDumpConfig();

  process.env.ARTIFACTORY_USER = 'artie';
  process.env.ARTIFACTORY_PASSWORD = 's3cr3t';

  const installNode = await import('./src/installNode');
  const secrets = new SecretsFixtureMap();

  const authOClient = {
    getSecrets: () => Promise.resolve(secrets),
    /* eslint-disable @typescript-eslint/no-unused-vars */
    getClientAccessToken: (clientId: string, clientSecret: string, audience?: string) =>
      Promise.resolve('access_token'),
    getCfg: () => svRunbookAuth0Config,
  };
  const svAppConfig = {
    onboardingName: svRunbookConfig.onboardingName,
    disableOnboardingParticipantPromotionDelay: false,
  };
  const validatorAppConfig = {
    walletUserName: svRunbookConfig.validatorWalletUser,
  };

  installNode.installNode(
    authOClient,
    svRunbookConfig.nodeName,
    svAppConfig,
    validatorAppConfig,
    () => Promise.resolve('dummy::partyId')
  );
}

main();
