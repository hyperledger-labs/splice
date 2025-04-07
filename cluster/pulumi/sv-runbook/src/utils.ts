import fetch from 'node-fetch';
import {
  config,
  CLUSTER_HOSTNAME,
  ExactNamespace,
  Auth0Client,
  installAuth0Secret,
  AppAndUiSecrets,
  uiSecret,
} from 'splice-pulumi-common';
import { svRunbookConfig } from 'splice-pulumi-common-sv';
import { retry } from 'splice-pulumi-common/src/retries';

export const DISABLE_ONBOARDING_PARTICIPANT_PROMOTION_DELAY = config.envFlag(
  'DISABLE_ONBOARDING_PARTICIPANT_PROMOTION_DELAY',
  false
);

export const SV_BENEFICIARY_VALIDATOR1 = config.envFlag('SV_BENEFICIARY_VALIDATOR1', true);

export async function getValidator1PartyId(): Promise<string> {
  return retry('getValidator1PartyId', 1000, 20, async () => {
    const validatorApiUrl = config.envFlag('SPLICE_DEPLOYMENT_SV_USE_INTERNAL_VALIDATOR_DNS')
      ? `http://validator-app.validator1:5003/api/validator/v0/validator-user`
      : `https://wallet.validator1.${CLUSTER_HOSTNAME}/api/validator/v0/validator-user`;
    const response = await fetch(validatorApiUrl);
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

export async function svAppSecrets(
  ns: ExactNamespace,
  auth0Client: Auth0Client,
  clientId: string
): Promise<AppAndUiSecrets> {
  return {
    appSecret: await installAuth0Secret(auth0Client, ns, 'sv', svRunbookConfig.auth0SvAppName),
    uiSecret: uiSecret(auth0Client, ns, 'sv', clientId),
  };
}
