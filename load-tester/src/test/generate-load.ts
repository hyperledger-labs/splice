import BigNumber from 'bignumber.js';

/* @ts-expect-error typings unavailable */
import { randomItem } from 'https://jslib.k6.io/k6-utils/1.4.0/index.js';
import { Counter, Trend } from 'k6/metrics';

import { Auth0Manager } from '../client/auth0/auth0';
import { logInUser } from '../client/auth0/helpers';
import { doIfOnboarded, sendAndWaitForTransferOffer } from '../client/validator/helpers';
import { ValidatorClient } from '../client/validator/validator';
import settings from '../settings';
import { pickTwoRandom } from '../utils';

export const options = { ...settings.options };

const transactionsStarted = new Counter('transactions_started');
const transactionsCompleted = new Counter('transactions_completed');
const transactionLatency = new Trend('transaction_latency', true);

type ValidatorConf = { walletBaseUrl: string; tokens: string[] };
type Data = ValidatorConf[];

export function setup(): Data {
  const validatorConfs: Data = [];

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

    validatorConfs[validatorIndex] = { tokens, walletBaseUrl: validator.walletBaseUrl };
  });

  return validatorConfs;
}

export default function (data: Data): void {
  // Pick a random available validator
  const validatorConf: ValidatorConf = randomItem(data);
  const userTokens = validatorConf.tokens;

  // Pick two random users from that validator
  const [senderIndex, recipientIndex] = pickTwoRandom(userTokens.length);

  const senderToken = userTokens[senderIndex];
  const recipientToken = userTokens[recipientIndex];

  const senderClient = new ValidatorClient(validatorConf.walletBaseUrl, senderToken);
  const receipientClient = new ValidatorClient(validatorConf.walletBaseUrl, recipientToken);

  doIfOnboarded(receipientClient, () => {
    doIfOnboarded(senderClient, () => {
      const balance = senderClient.v0.wallet.getBalance();
      if (balance && BigNumber(balance?.effective_unlocked_qty).lte(10)) {
        senderClient.v0.wallet.tap('1000.0');
      }
      transactionsStarted.add(1);
      sendAndWaitForTransferOffer(senderClient, receipientClient, {
        completed: transactionsCompleted,
        latency: transactionLatency,
      });
    });
  });
}
