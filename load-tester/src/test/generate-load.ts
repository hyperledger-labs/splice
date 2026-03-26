// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import BigNumber from 'bignumber.js';
import { Gauge } from 'k6/metrics';



import { Auth0Manager } from '../client/auth0/auth0';
import { logInUser } from '../client/auth0/helpers';
import { encodeJwtHmac256 } from '../client/jwt';
import { doIfOnboarded, sendAndWaitForTransferOffer, waitForBalance } from '../client/validator/helpers';
import settings from '../settings';
import { pickTwoRandomUsers, syncRetryUndefined } from '../utils';


export const options = { ...settings.options };

const validatorOperatorBalance = new Gauge('validator_operator_balance');

export type ValidatorConf = {
  walletBaseUrl: string;
  adminToken: string;
  userTokens: string[];
  userFeatured: boolean[];
};

export function setup(): ValidatorConf[] {
  const validatorConfs: ValidatorConf[] = [];

  settings.validators.forEach((validator, validatorIndex) => {
    let tokens: string[] = [];
    let featured: boolean[] = [];

    if (validator.auth.kind === 'oauth') {
      const auth0 = new Auth0Manager(
        validator.auth.oauthDomain,
        validator.auth.oauthClientId,
        validator.auth.audience,
        validator.walletBaseUrl,
        validator.auth.managementApi,
      );

      for (let i = 0; i < settings.usersPerValidator; i++) {
        const t = logInUser(auth0, `user-${i}@cn-load-tester.com`, validator.auth.usersPassword);
        tokens = [...tokens, t];
        featured = [...featured, i < settings.featuredUsersPerValidator];
      }

      const adminToken = logInUser(
        auth0,
        validator.auth.admin.email,
        validator.auth.admin.password,
      );

      validatorConfs[validatorIndex] = {
        adminToken,
        userTokens: tokens,
        userFeatured: featured,
        walletBaseUrl: validator.walletBaseUrl,
      };
    } else if (validator.auth.kind === 'self-signed') {
      const secret = validator.auth.secret;
      const aud = validator.auth.audience;
      const expiryDate = new Date();
      expiryDate.setDate(expiryDate.getDate() + 10);
      const exp = expiryDate.valueOf();
      const adminToken = encodeJwtHmac256(
        {
          sub: validator.auth.user,
          aud,
          exp,
        },
        secret,
      );

      const userTokens = Array(settings.usersPerValidator)
        .fill(0)
        .map((_, i) =>
          encodeJwtHmac256(
            {
              sub: `v-${validatorIndex}-user-${i}`,
              aud,
              exp,
            },
            secret,
          ),
        );

      const userFeatured = Array(settings.usersPerValidator)
        .fill(0)
        .map((_, i) => i < settings.featuredUsersPerValidator);

      validatorConfs[validatorIndex] = {
        adminToken,
        userTokens,
        userFeatured,
        walletBaseUrl: validator.walletBaseUrl,
      };
    }
  });

  return validatorConfs;
}

export default function (data: ValidatorConf[]): void {
  const { adminClient, senderClient, recipientClient } = pickTwoRandomUsers(data);

  // Track the admin's balance on non-devnet to send out top-up alerts
  if (!settings.isDevNet) {
    const adminBalance = syncRetryUndefined(adminClient.v0.wallet.getBalance);
    if (adminBalance) {
      validatorOperatorBalance.add(BigNumber(adminBalance.effective_unlocked_qty).toNumber());
    }
  }

  doIfOnboarded(recipientClient, () => {
    doIfOnboarded(senderClient, () => {
      waitForBalance(senderClient, 10, '1000.0', adminClient, settings.isDevNet);
      sendAndWaitForTransferOffer(senderClient, recipientClient, '1.00');
    });
  });
}
