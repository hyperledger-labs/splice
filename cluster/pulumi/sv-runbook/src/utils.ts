// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import {
  config,
  CLUSTER_HOSTNAME,
} from '@lfdecentralizedtrust/splice-pulumi-common';
import { retry } from '@lfdecentralizedtrust/splice-pulumi-common/src/retries';
import fetch from 'node-fetch';

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
