import BigNumber from 'bignumber.js';
import { Counter, Trend } from 'k6/metrics';

import { ValidatorClient } from '../client/validator';
import settings from '../settings';
import { Auth0Manager } from '../utils/auth';
import { doIfOnboarded, pickTwoRandom, sendAndWaitForTransferOffer } from '../utils/utils';

export const options = { ...settings.options };

const transactionsStarted = new Counter('transactions_started');
const transactionsCompleted = new Counter('transactions_completed');
const transactionLatency = new Trend('transaction_latency', true);

type Data = {
  tokens: string[];
};

export function setup(): Data {
  const auth0 = new Auth0Manager(
    settings.auth.oauthDomain,
    settings.auth.oauthClientId,
    settings.walletBaseUrl,
  );

  // TODO(#8986): just supply a shared password in the config, and register/use users dynamically
  const userPassword = settings.auth.userCredentials.split(':')[1];

  const user1Token = auth0.authorizationCodeGrant(settings.auth.userCredentials);
  const user2Token = auth0.authorizationCodeGrant(`user-2@cn-load-tester.com:${userPassword}`);

  return { tokens: [user1Token, user2Token] };
}

export default function (data: Data): void {
  const [senderIndex, recipientIndex] = pickTwoRandom(data.tokens.length);

  const senderToken = data.tokens[senderIndex];
  const recipientToken = data.tokens[recipientIndex];

  const senderClient = new ValidatorClient(settings.walletBaseUrl, senderToken);
  const receipientClient = new ValidatorClient(settings.walletBaseUrl, recipientToken);

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
