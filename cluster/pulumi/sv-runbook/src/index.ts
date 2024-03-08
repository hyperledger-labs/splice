import * as pulumi from '@pulumi/pulumi';
import { Auth0ClusterConfig, Auth0Fetch, infraStack, requireEnv } from 'cn-pulumi-common';

import { installNode } from './installNode';
import {
  SV_NAME,
  SV_NAMESPACE,
  ENABLE_ONBOARDING_PARTICIPANT_PROMOTION_DELAY,
  validatorWalletUserName,
} from './utils';

async function auth0CacheAndInstallNode(auth0Fetch: Auth0Fetch) {
  await auth0Fetch.loadAuth0Cache();

  const svAppConfig = {
    onboardingName: SV_NAME,
    enableOnboardingParticipantPromotionDelay: ENABLE_ONBOARDING_PARTICIPANT_PROMOTION_DELAY,
  };
  const validatorAppConfig = {
    walletUserName: validatorWalletUserName,
  };

  await installNode(auth0Fetch, SV_NAMESPACE, svAppConfig, validatorAppConfig);

  await auth0Fetch.saveAuth0Cache();
}

async function main() {
  const auth0ClusterCfg = infraStack.requireOutput('auth0') as pulumi.Output<Auth0ClusterConfig>;
  const auth0FetchOutput = auth0ClusterCfg.svRunbook.apply(cfg => {
    cfg.auth0MgtClientSecret = requireEnv('AUTH0_SV_MANAGEMENT_API_CLIENT_SECRET');
    return new Auth0Fetch(cfg);
  });

  auth0FetchOutput.apply(auth0Fetch => {
    // eslint-disable-next-line @typescript-eslint/no-floating-promises
    auth0CacheAndInstallNode(auth0Fetch);
  });
}

// eslint-disable-next-line @typescript-eslint/no-floating-promises
main();
