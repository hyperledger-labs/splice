import { sleep } from 'k6';
import { Counter, Trend } from 'k6/metrics';

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
  completed: Counter;
  latency: Trend;
}

export function sendAndWaitForTransferOffer(
  sender: ValidatorClient,
  receiver: ValidatorClient,
  metrics: MetricsContext,
): void {
  const startTime = Date.now();

  const transferOffer = sender.v0.wallet.createTransferOffer('1', receiver.partyId()!);
  if (transferOffer) {
    let receivingOffer = receiver.v0.wallet
      .listTransferOffers()
      ?.offers.find(o => o.contract_id === transferOffer.offer_contract_id);
    let retries = 5;
    while (retries >= 0) {
      if (!receivingOffer) {
        sleep(1);
        receivingOffer = receiver.v0.wallet
          .listTransferOffers()
          ?.offers.find(o => o.contract_id === transferOffer.offer_contract_id);
      }
      retries = retries - 1;
    }

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
