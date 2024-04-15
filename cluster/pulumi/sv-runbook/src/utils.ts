import { envFlag, isDevNet, requireEnv } from 'cn-pulumi-common';
import { retry } from 'cn-pulumi-common/src/retries';
import fetch from 'node-fetch';

export const CLUSTER_BASENAME = requireEnv(
  'GCP_CLUSTER_BASENAME',
  'The cluster in which this chart is being installed'
);
export const TARGET_CLUSTER = requireEnv(
  'TARGET_CLUSTER',
  'the cluster in which the global domain is running'
);
export const SV_NAME = 'DA-Helm-Test-Node';
export const SV_NAMESPACE = 'sv';

export const DISABLE_ONBOARDING_PARTICIPANT_PROMOTION_DELAY = envFlag(
  'DISABLE_ONBOARDING_PARTICIPANT_PROMOTION_DELAY',
  false
);

export const SV_BENEFICIARY_VALIDATOR1 = envFlag('SV_BENEFICIARY_VALIDATOR1', true);

export async function getValidator1PartyId(): Promise<string> {
  return retry('getValidator1PartyId', 1000, 100, async () => {
    const response = await fetch(
      `https://wallet.validator1.${CLUSTER_BASENAME}.network.canton.global/api/validator/v0/validator-user`
    );
    const json = await response.json();
    if (!response.ok) {
      throw new Error(`Response is not OK: ${JSON.stringify(json)}`);
    } else if (!json.party_id) {
      throw new Error(`JSON does not contain party_id: ${JSON.stringify(json)}`);
    } else {
      return json.party_id;
    }
  });
}

// Default to admin@sv-dev.com (devnet) or admin@sv.com (non devnet) at the sv-test tenant by default
export const validatorWalletUserName = isDevNet
  ? 'auth0|64b16b9ff7a0dfd00ea3704e'
  : 'auth0|64553aa683015a9687d9cc2e';

export const DEFAULT_AUDIENCE = 'https://canton.network.global';
