import BigNumber from 'bignumber.js';

/* @ts-expect-error typings unavailable */
import { randomItem } from 'https://jslib.k6.io/k6-utils/1.4.0/index.js';
import { Counter, Gauge, Trend } from 'k6/metrics';

import { Auth0Manager } from '../client/auth0/auth0';
import { logInUser } from '../client/auth0/helpers';
import {
  doIfOnboarded,
  sendAndWaitForTransferOffer,
  waitForBalance,
} from '../client/validator/helpers';
import { ValidatorClient } from '../client/validator/validator';
import settings from '../settings';
import { pickTwoRandom, syncRetryUndefined } from '../utils';

export const options = { ...settings.options };

const transactionsStarted = new Counter('transactions_started');
const transactionsCompleted = new Counter('transactions_completed');
const transactionLatency = new Trend('transaction_latency', true);
const validatorOperatorBalance = new Gauge('validator_operator_balance');

type ValidatorConf = {
  walletBaseUrl: string;
  adminToken: string;
  userTokens: string[];
};

export function setup(): ValidatorConf[] {
  const validatorConfs: ValidatorConf[] = [];

  settings.validators.forEach((validator, validatorIndex) => {
    let tokens: string[] = [];

    const auth0 = new Auth0Manager(
      validator.auth.oauthDomain,
      validator.auth.oauthClientId,
      validator.walletBaseUrl,
      validator.auth.managementApi,
    );

    for (let i = 0; i < settings.usersPerValidator; i++) {
      const t = logInUser(auth0, `user-${i}@cn-load-tester.com`, validator.auth.usersPassword);
      tokens = [...tokens, t];
    }

    const adminToken = logInUser(auth0, validator.auth.admin.email, validator.auth.admin.password);

    validatorConfs[validatorIndex] = {
      adminToken,
      userTokens: tokens,
      walletBaseUrl: validator.walletBaseUrl,
    };
  });

  return validatorConfs;
}

export default function (data: ValidatorConf[]): void {
  // Pick a random available validator
  const validatorConf: ValidatorConf = randomItem(data);
  const { adminToken, walletBaseUrl, userTokens } = validatorConf;

  // Pick two random users from that validator
  const [senderIndex, recipientIndex] = pickTwoRandom(userTokens.length);

  const senderToken = userTokens[senderIndex];
  const recipientToken = userTokens[recipientIndex];

  const adminClient = new ValidatorClient(walletBaseUrl, adminToken);
  const senderClient = new ValidatorClient(walletBaseUrl, senderToken);
  const receipientClient = new ValidatorClient(walletBaseUrl, recipientToken);

  // Track the admin's balance on non-devnet to send out top-up alerts
  if (!settings.isDevNet) {
    const adminBalance = syncRetryUndefined(adminClient.v0.wallet.getBalance);
    if (adminBalance) {
      validatorOperatorBalance.add(BigNumber(adminBalance.effective_unlocked_qty).toNumber());
    }
  }

  const metrics = {
    validatorOperatorBalance,
    started: transactionsStarted,
    completed: transactionsCompleted,
    latency: transactionLatency,
  };

  doIfOnboarded(receipientClient, () => {
    doIfOnboarded(senderClient, () => {
      waitForBalance(senderClient, 10, '1000.0', adminClient, settings.isDevNet, metrics);
      sendAndWaitForTransferOffer(senderClient, receipientClient, '1.00', metrics);
    });
  });
}
