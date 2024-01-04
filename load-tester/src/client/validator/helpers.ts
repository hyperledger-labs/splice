import BigNumber from 'bignumber.js';
import { Counter, Trend } from 'k6/metrics';

import { syncRetryUndefined } from '../../utils';
import { GetBalanceResponse } from './models';
import { ValidatorClient } from './validator';

export function doIfOnboarded(validatorClient: ValidatorClient, action: () => void): void {
  const userStatus = validatorClient.v0.wallet.userStatus();
  if (userStatus?.user_onboarded && userStatus?.user_wallet_installed) {
    action();
  } else {
    validatorClient.v0.register();
  }
}

interface MetricsContext {
  started: Counter;
  completed: Counter;
  latency: Trend;
}

export function sendAndWaitForTransferOffer(
  sender: ValidatorClient,
  receiver: ValidatorClient,
  amount: string,
  metrics: MetricsContext,
): void {
  metrics.started.add(1);
  const startTime = Date.now();

  const receiverParty = receiver.partyId()!; // assumes doIfOnboarded has been called for receiver
  const transferOffer = sender.v0.wallet.createTransferOffer(amount, receiverParty);
  if (transferOffer) {
    const receivingOffer = syncRetryUndefined(
      () =>
        receiver.v0.wallet
          .listTransferOffers()
          ?.offers.find(o => o.contract_id === transferOffer.offer_contract_id),
    );

    if (receivingOffer) {
      receiver.v0.wallet.acceptTransferOffer(receivingOffer.contract_id);
      metrics.completed.add(1);
      // TODO(#9052) -- include time to show up in transaction history
      metrics.latency.add(Date.now() - startTime);
    }
  } else {
    console.error('We expected a transfer offer from the sending side');
  }
}

export function waitForBalance(
  client: ValidatorClient,
  minBalanceThreshold: number,
  topUpAmount: string,
  admin: ValidatorClient,
  isDevNet: boolean,
  metrics: MetricsContext,
): GetBalanceResponse | undefined {
  const balance = syncRetryUndefined(client.v0.wallet.getBalance);
  if (balance && BigNumber(balance?.effective_unlocked_qty).lte(minBalanceThreshold)) {
    if (isDevNet) {
      client.v0.wallet.tap(topUpAmount);
      const balance = syncRetryUndefined(client.v0.wallet.getBalance);
      return balance;
    } else {
      // In non-devnet environments, users must be sent coins from the validator admin. The validator admin will have to have
      //  its wallet balance topped up manually
      sendAndWaitForTransferOffer(admin, client, topUpAmount, metrics);
      const balance = syncRetryUndefined(client.v0.wallet.getBalance);

      if (!balance || BigNumber(balance.effective_unlocked_qty).lte(minBalanceThreshold)) {
        console.error('Failed to topup the client from the validator operator');
      }
    }
  } else {
    return balance;
  }
}
