import * as pulumi from '@pulumi/pulumi';
import { Auth0ClusterConfig, Auth0Fetch, infraStack, config } from 'cn-pulumi-common';

import { installNode } from './installNode';
import {
  SV_NAME,
  SV_NAMESPACE,
  DISABLE_ONBOARDING_PARTICIPANT_PROMOTION_DELAY,
  validatorWalletUserName,
  SV_BENEFICIARY_VALIDATOR1,
  getValidator1PartyId,
} from './utils';

async function auth0CacheAndInstallNode(auth0Fetch: Auth0Fetch) {
  await auth0Fetch.loadAuth0Cache();

  const svAppConfig = {
    onboardingName: SV_NAME,
    disableOnboardingParticipantPromotionDelay: DISABLE_ONBOARDING_PARTICIPANT_PROMOTION_DELAY,
  };
  const validatorAppConfig = {
    walletUserName: validatorWalletUserName,
  };

  const resolveValidator1PartyId = SV_BENEFICIARY_VALIDATOR1 ? getValidator1PartyId : undefined;

  await installNode(
    auth0Fetch,
    SV_NAMESPACE,
    svAppConfig,
    validatorAppConfig,
    resolveValidator1PartyId
  );

  await auth0Fetch.saveAuth0Cache();
}

async function main() {
  const auth0ClusterCfg = infraStack.requireOutput('auth0') as pulumi.Output<Auth0ClusterConfig>;
  if (!auth0ClusterCfg.svRunbook) {
    throw new Error('missing sv runbook auth0 output');
  }
  const auth0FetchOutput = auth0ClusterCfg.svRunbook.apply(cfg => {
    if (!cfg) {
      throw new Error('missing sv runbook auth0 output');
    }
    cfg.auth0MgtClientSecret = config.requireEnv('AUTH0_SV_MANAGEMENT_API_CLIENT_SECRET');
    return new Auth0Fetch(cfg);
  });

  auth0FetchOutput.apply(auth0Fetch => {
    // eslint-disable-next-line @typescript-eslint/no-floating-promises
    auth0CacheAndInstallNode(auth0Fetch);
  });
}

// eslint-disable-next-line @typescript-eslint/no-floating-promises
main();
